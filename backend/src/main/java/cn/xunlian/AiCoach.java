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
    private final LocalAiSettings settings;
    private final String endpoint = System.getenv("AI_BASE_URL");
    private final String apiKey = System.getenv("AI_API_KEY");
    private final String model = System.getenv("AI_MODEL");
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public AiCoach(ObjectMapper json, CoachStore store, LocalAiSettings settings) {
        this.json = json;
        this.store = store;
        this.settings = settings;
    }

    boolean configured(LocalAiSettings.Status status) {
        return status.hasLocalKey() || environmentConfigured();
    }

    private boolean environmentConfigured() {
        return endpoint != null && !endpoint.isBlank() && apiKey != null && !apiKey.isBlank()
                && model != null && !model.isBlank();
    }

    private LocalAiSettings.Credentials credentials(Account account) {
        LocalAiSettings.Credentials local = settings.read(account);
        if (local != null || !environmentConfigured()) return local;
        return new LocalAiSettings.Credentials(endpoint, model, apiKey);
    }

    /**
     * 用模型细化规则方案。记忆与画像一起进上下文，模型给出的引用会逐条校验，
     * 只有确实出自记忆或画像的条目才会被采用。
     */
    public void refine(Account account, Proposal proposal, Workspace workspace, List<Problem> candidates, String instruction,
                       List<String> memory, ProfileModel profile) {
        proposal.engine = "rules";
        proposal.basis = new ArrayList<>(memory.stream().limit(3).toList());
        try {
            LocalAiSettings.Credentials config = credentials(account);
            if (config == null) {
                proposal.modelMessage = "当前使用规则教练。配置模型服务后，可根据复盘文字进一步调整。";
                return;
            }
            List<Problem> pool = candidates.stream().limit(60).toList();
            Map<String, Problem> byId = new HashMap<>();
            pool.forEach(problem -> byId.put(problem.id, problem));
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("profile", workspace.profile);
            context.put("profileSummary", profile == null ? "" : profile.summary);
            context.put("weaknesses", profile == null ? List.of() : profile.weaknesses);
            context.put("memory", memory);
            context.put("recentFeedback", workspace.feedback.stream().skip(Math.max(0, workspace.feedback.size() - 8)).toList());
            context.put("baselinePlan", proposal.plan);
            context.put("candidates", pool);
            context.put("instruction", instruction);
            String system = "你是 ACM 训练教练。根据反馈和 memory 调整本周尚未完成的任务。只选 candidates 中的 problemId。"
                    + "保留已完成任务由服务端负责，你只返回新安排的待做任务。遵守 baselinePlan.dailyMinutes 的每天预算和 weekStart，禁止安排过去日期。"
                    + "用时至少为题目的 minutes。reason 必须说明调整依据，并引用 memory 中的观察。"
                    + "输出 JSON 对象 {reason:中文调整理由,basis:[引用到的 memory 原文],tasks:[{problemId:候选题ID,day:0到6,minutes:整数,reason:中文推荐理由}]}。最多6道题。";
            JsonNode result = json.readTree(complete(config, system, context, 40));
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
            refined.basis = citations(result, memory, profile);
            proposal.plan = refined;
            proposal.reason = refined.explanation;
            proposal.engine = "model";
            proposal.basis = new ArrayList<>(refined.basis);
            proposal.modelMessage = "模型已生成方案，题目、时间预算与引用依据已通过检查。";
        } catch (Exception ex) {
            proposal.modelMessage = "模型调用或结果校验未通过，本次使用规则方案。可检查模型配置后重试。";
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }

    /** 只接受能对应到记忆或画像原文的引用，避免模型编造依据。 */
    private List<String> citations(JsonNode result, List<String> memory, ProfileModel profile) {
        List<String> allowed = new ArrayList<>(memory);
        if (profile != null) {
            allowed.add(profile.summary);
            allowed.addAll(profile.weaknesses);
        }
        List<String> picked = new ArrayList<>();
        for (JsonNode node : result.path("basis")) {
            String text = node.asText("").trim();
            if (text.isEmpty() || picked.contains(text)) continue;
            boolean grounded = allowed.stream().anyMatch(item -> item != null && !item.isBlank()
                    && (item.contains(text) || text.contains(item)));
            if (grounded) picked.add(text.length() > 160 ? text.substring(0, 160) : text);
            if (picked.size() == 3) break;
        }
        return picked.isEmpty() ? new ArrayList<>(memory.stream().limit(2).toList()) : picked;
    }

    /** 代码审查的模型通道；失败时保留规则结果。 */
    public boolean reviewCode(Account account, CodeReview review, Problem problem, List<SourceFile> files) {
        try {
            LocalAiSettings.Credentials config = credentials(account);
            if (config == null) return false;
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("problem", problem == null ? Map.of("id", Objects.toString(review.problemId, "")) : problem);
            List<Map<String, Object>> snippets = new ArrayList<>();
            Map<String, Integer> lineCounts = new LinkedHashMap<>();
            for (SourceFile file : files) {
                String content = file.content.length() > 8000 ? file.content.substring(0, 8000) : file.content;
                int lines = file.content.split("\n", -1).length;
                lineCounts.put(file.name, lines);
                snippets.add(Map.of("name", file.name, "lines", lines, "content", content));
            }
            context.put("files", snippets);
            String system = "你是 C++ 竞赛代码审查教练。只针对给定片段，结合题目约束指出边界、复杂度、精度、溢出等问题。"
                    + "行号必须是片段内的真实行号（从 1 开始），不要推测未提供的代码。"
                    + "输出 JSON {summary:中文总评,issues:[{file,line,severity:high|medium|low,kind:中文类别,message:问题说明,suggestion:修改建议}]}，最多 10 条。";
            JsonNode result = json.readTree(complete(config, system, context, 50));
            List<Finding> extra = new ArrayList<>();
            for (JsonNode node : result.path("issues")) {
                String file = node.path("file").asText("");
                int line = node.path("line").asInt(0);
                Integer limit = lineCounts.get(file);
                if (limit == null || line < 1 || line > limit) continue;
                String severity = node.path("severity").asText("low");
                if (!List.of("high", "medium", "low").contains(severity)) severity = "low";
                Finding item = new Finding();
                item.file = file;
                item.line = line;
                item.severity = severity;
                item.kind = clip(node.path("kind").asText("实现"), 12);
                item.message = clip(node.path("message").asText(""), 300);
                item.suggestion = clip(node.path("suggestion").asText(""), 300);
                if (item.message.isBlank()) continue;
                extra.add(item);
                if (extra.size() == 10) break;
            }
            review.findings.addAll(extra);
            String summary = clip(result.path("summary").asText(""), 300);
            if (!summary.isBlank()) review.summary = summary;
            return true;
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }

    JsonNode workspaceReply(Account account, String system, Object context) {
        try {
            LocalAiSettings.Credentials config = credentials(account);
            if (config != null) return json.readTree(complete(config, system, context, 50));
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
        }
        return json.createObjectNode();
    }

    private String complete(LocalAiSettings.Credentials config, String system, Object context, int timeoutSeconds) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.model());
        body.put("messages", List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", json.writeValueAsString(context))));
        body.put("temperature", 0.3);
        body.put("response_format", Map.of("type", "json_object"));
        String base = config.baseUrl().replaceAll("/+$", "");
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/chat/completions"))
                .timeout(Duration.ofSeconds(timeoutSeconds)).header("Authorization", "Bearer " + config.apiKey())
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalArgumentException("模型服务返回 HTTP " + response.statusCode());
        String content = json.readTree(response.body()).path("choices").path(0).path("message").path("content").asText();
        if (config.apiKey().length() >= 8 && content.contains(config.apiKey())) {
            throw new IllegalArgumentException("模型返回中包含认证信息");
        }
        return content;
    }

    private String clip(String text, int limit) {
        if (text == null) return "";
        return text.length() <= limit ? text : text.substring(0, limit);
    }
}
