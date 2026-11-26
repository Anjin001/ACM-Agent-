package cn.xunlian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import static cn.xunlian.Models.*;

@Service
public class AiCoach {
    private final ObjectMapper json;
    private final CoachStore store;
    private final String endpoint = System.getenv("AI_BASE_URL");
    private final String apiKey = System.getenv("AI_API_KEY");
    private final String model = System.getenv("AI_MODEL");
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public AiCoach(ObjectMapper json, CoachStore store) { this.json = json; this.store = store; }

    public boolean configured() { return endpoint != null && apiKey != null && model != null && !apiKey.isBlank() && !model.isBlank(); }

    public void refine(Proposal proposal, Workspace workspace, List<Problem> candidates, String instruction) {
        proposal.engine = "rules";
        if (!configured()) {
            proposal.modelMessage = "当前使用规则教练。配置模型服务后，可根据复盘文字进一步调整。";
            return;
        }
        try {
            List<Problem> pool = candidates.stream().limit(60).toList();
            Map<String, Problem> byId = new HashMap<>();
            pool.forEach(problem -> byId.put(problem.id, problem));
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("profile", workspace.profile);
            context.put("recentFeedback", workspace.feedback.stream().skip(Math.max(0, workspace.feedback.size() - 8)).toList());
            context.put("baselinePlan", proposal.plan);
            context.put("candidates", pool);
            context.put("instruction", instruction);
            String system = "你是 ACM 训练教练。根据反馈调整本周尚未完成的任务。只选 candidates 中的 problemId。"
                    + "保留已完成任务由服务端负责，你只返回新安排的待做任务。遵守 baselinePlan.dailyMinutes 的每天预算和 weekStart，禁止安排过去日期。"
                    + "用时至少为题目的 minutes。输出 JSON 对象 {reason:中文调整理由,tasks:[{problemId:候选题ID,day:0到6,minutes:整数,reason:中文推荐理由}]}。最多6道题。";
            Map<String, Object> body = Map.of("model", model, "messages", List.of(Map.of("role", "system", "content", system), Map.of("role", "user", "content", json.writeValueAsString(context))), "temperature", 0.3, "response_format", Map.of("type", "json_object"));
            String base = endpoint.replaceAll("/+$", "");
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/chat/completions"))
                    .timeout(Duration.ofSeconds(40)).header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IllegalArgumentException("模型服务返回 HTTP " + response.statusCode());
            String content = json.readTree(response.body()).path("choices").path(0).path("message").path("content").asText();
            JsonNode result = json.readTree(content);
            JsonNode tasks = result.path("tasks");
            if (!tasks.isArray() || tasks.size() == 0 || tasks.size() > 6) throw new IllegalArgumentException("模型返回的任务数量无效");
            Plan refined = store.copy(proposal.plan, Plan.class);
            refined.tasks.removeIf(task -> task.status.equals("todo"));
            int[] used = new int[7];
            Set<String> seen = new HashSet<>();
            refined.tasks.forEach(task -> { used[task.day] += task.minutes; seen.add(task.problemId); });
            int firstDay = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).getDayOfWeek().getValue() - 1;
            for (JsonNode item : tasks) {
                String id = item.path("problemId").asText();
                int day = item.path("day").asInt(-1);
                int minutes = item.path("minutes").asInt(-1);
                Problem problem = byId.get(id);
                if (problem == null || !seen.add(id) || day < firstDay || day > 6 || minutes < problem.minutes || minutes > 480) throw new IllegalArgumentException("模型任务未通过候选题或时间检查");
                if (used[day] + minutes > refined.dailyMinutes.get(day)) throw new IllegalArgumentException("模型任务超出当天时间预算");
                used[day] += minutes;
                Task task = new Task();
                task.id = UUID.randomUUID().toString();
                task.problemId = id;
                task.day = day;
                task.minutes = minutes;
                String taskReason = item.path("reason").asText("根据训练反馈安排。");
                task.reason = taskReason.substring(0, Math.min(300, taskReason.length()));
                refined.tasks.add(task);
            }
            String reason = result.path("reason").asText("根据近期训练反馈调整后续练习。");
            refined.tasks.sort(Comparator.comparingInt(task -> task.day));
            refined.engine = "model";
            refined.explanation = reason.substring(0, Math.min(1500, reason.length()));
            proposal.plan = refined;
            proposal.reason = refined.explanation;
            proposal.engine = "model";
            proposal.modelMessage = "模型已生成方案，题目与时间预算已通过检查。";
        } catch (Exception ex) {
            proposal.modelMessage = "模型调用或结果校验未通过，本次使用规则方案。可检查模型配置后重试。";
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }
}
