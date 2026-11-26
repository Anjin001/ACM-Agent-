package cn.xunlian;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static cn.xunlian.Models.*;

/** 把复盘、代码审查与目标设置沉淀成「有出处的观察」，供画像与计划引用。 */
@Service
public class CoachMemory {
    private final MemoryStore memory;

    public CoachMemory(MemoryStore memory) {
        this.memory = memory;
    }

    public void observeGoal(long userId, Profile profile) {
        if (profile == null || profile.goal == null || profile.goal.isBlank()) return;
        memory.upsert(userId, "goal", "训练目标：" + profile.goal.trim(), "来自个人设置", "profile", null, 1.0);
        if (profile.handle != null && !profile.handle.isBlank()) {
            memory.upsert(userId, "constraint", "CF handle 关联：" + profile.handle, "用于同步公开提交记录", "profile", null, 0.5);
        }
    }

    /** 复盘保存时调用：抽取错因并写入记忆，同时把标签回填到反馈上。 */
    public List<String> observeFeedback(long userId, Feedback feedback, Problem problem) {
        List<String> tags = new ArrayList<>();
        String topic = problem == null || problem.tags.isEmpty() ? "" : problem.tags.get(0);
        for (Nlp.Cause cause : Nlp.causes(feedback.note)) {
            tags.add(cause.tag());
            String statement = (topic.isBlank() ? "近期训练" : "「" + topic + "」训练")
                    + "中容易在" + cause.tag() + "上出错";
            String detail = "来自 " + (problem == null ? feedback.problemId : problem.code)
                    + " 的复盘：" + clamp(feedback.note, 60);
            memory.upsert(userId, "error_pattern", statement, detail, "feedback", feedback.id, cause.weight());
        }
        if (problem != null && problem.minutes > 0 && feedback.minutes > 0
                && "independent".equals(feedback.outcome) && feedback.minutes <= problem.minutes * 0.7) {
            String statement = (topic.isBlank() ? "近期题目" : "「" + topic + "」题目") + "可以明显快于预估时间独立完成";
            memory.upsert(userId, "strength", statement,
                    "来自 " + problem.code + "：预估 " + problem.minutes + " 分钟，实际 " + feedback.minutes + " 分钟",
                    "feedback", feedback.id, 0.8);
        }
        return tags;
    }

    public void observeReview(long userId, CodeReview review) {
        for (Finding finding : review.findings) {
            if ("low".equals(finding.severity)) continue;
            String statement = "代码审查反复提示" + finding.kind + "问题：" + clamp(finding.message, 40);
            memory.upsert(userId, "code_issue", statement,
                    finding.file + " 第 " + finding.line + " 行", "review", review.id, "high".equals(finding.severity) ? 1.2 : 0.7);
        }
        memory.event(userId, "code_review", "审查 " + review.files.size() + " 个文件，发现 " + review.findings.size() + " 处问题", review);
    }

    /** 生成计划时可引用的观察：目标优先，其次按权重。 */
    public List<String> activeStatements(long userId, int limit) {
        return memory.list(userId, true).stream()
                .sorted(Comparator.comparing((Observation item) -> item.kind.equals("goal") ? 0 : 1)
                        .thenComparing(Comparator.comparingDouble((Observation item) -> item.weight).reversed()))
                .limit(limit)
                .map(item -> item.statement)
                .toList();
    }

    public List<Observation> active(long userId) {
        return memory.list(userId, true);
    }

    public void event(long userId, String type, String summary) {
        memory.event(userId, type, summary, null);
    }

    private String clamp(String text, int limit) {
        if (text == null) return "";
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= limit ? flat : flat.substring(0, limit) + "…";
    }
}
