package cn.xunlian;

import org.springframework.stereotype.Service;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

import static cn.xunlian.Models.*;

@Service
public class Planner {
    private final CoachStore store;

    public Planner(CoachStore store) { this.store = store; }

    public List<Problem> candidates(long userId, Workspace workspace) {
        Set<String> excluded = store.excluded(userId, workspace);
        return store.problems().stream()
                .filter(problem -> !excluded.contains(problem.id))
                .filter(problem -> problem.tags.stream().anyMatch(workspace.profile.topics::contains))
                .filter(problem -> problem.rating == null || (problem.rating >= workspace.profile.level - 200 && problem.rating <= workspace.profile.level + 200))
                .sorted(Comparator.comparingInt((Problem problem) -> problem.minutes).thenComparing(problem -> problem.id))
                .toList();
    }

    public Plan generate(long userId, Workspace workspace, String instruction) {
        return generate(userId, workspace, instruction, List.of());
    }

    /**
     * 生成周计划。时间要求交给 Nlp 解析成槽位；排期用时按历史实际用时校准；
     * 计划会带上依据（记忆原文），便于在界面上说明「为什么这样排」。
     */
    public Plan generate(long userId, Workspace workspace, String instruction, List<String> memory) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        String monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString();
        Plan plan = new Plan();
        plan.weekStart = monday;
        plan.dailyMinutes = new ArrayList<>(workspace.profile.dailyMinutes);
        plan.version = workspace.plan == null ? 1 : workspace.plan.version + 1;
        if (workspace.plan != null && workspace.plan.weekStart.equals(monday)) {
            plan.tasks = workspace.plan.tasks.stream().filter(task -> !task.status.equals("todo"))
                    .map(task -> store.copy(task, Task.class)).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        }
        plan.timeEcho = applyTimeInstruction(plan.dailyMinutes, instruction);

        Map<String, Double> calibration = calibration(workspace);
        Set<String> used = new HashSet<>();
        plan.tasks.forEach(task -> used.add(task.problemId));
        Map<String, Problem> catalog = new HashMap<>();
        store.problems().forEach(problem -> catalog.put(problem.id, problem));
        var assessment = workspace.assessment != null && !workspace.assessment.results.isEmpty()
                ? workspace.assessment : workspace.assessmentHistory.stream().filter(test -> !test.results.isEmpty()).findFirst().orElse(null);
        String assessmentFocus = assessment == null ? "" : assessment.results.stream()
                .filter(result -> "attempted".equals(result.status()))
                .map(result -> catalog.get(result.problemId())).filter(Objects::nonNull)
                .flatMap(problem -> problem.tags.stream()).filter(workspace.profile.topics::contains)
                .findFirst().orElse("");
        String focus = !assessmentFocus.isEmpty() ? assessmentFocus : workspace.feedback.stream()
                .filter(feedback -> !feedback.outcome.equals("independent"))
                .reduce((first, second) -> second)
                .map(feedback -> catalog.get(feedback.problemId))
                .map(problem -> problem.tags.isEmpty() ? "" : problem.tags.get(0)).orElse("");
        List<Problem> available = new ArrayList<>(candidates(userId, workspace));
        if (!assessmentFocus.isEmpty()) available.removeIf(problem -> assessment.problemIds.contains(problem.id));
        available.sort(Comparator.comparingInt((Problem problem) -> problem.tags.contains(focus) ? 0 : 1)
                .thenComparingInt(problem -> problem.minutes));
        int firstDay = today.getDayOfWeek().getValue() - 1;
        int added = 0;
        boolean usedAssessment = false;
        for (int day = firstDay; day < 7; day++) {
            final int taskDay = day;
            int usedMinutes = plan.tasks.stream().filter(task -> task.day == taskDay).mapToInt(task -> task.minutes).sum();
            int remaining = plan.dailyMinutes.get(day) - usedMinutes;
            for (Problem problem : available) {
                if (used.contains(problem.id) || added >= 6) continue;
                int minutes = estimate(problem, calibration);
                if (minutes > remaining) continue;
                Task task = new Task();
                task.id = UUID.randomUUID().toString();
                task.problemId = problem.id;
                task.day = day;
                task.minutes = minutes;
                task.reason = problem.tags.contains(focus)
                        ? (!assessmentFocus.isEmpty() ? "最近短测中该专题有尝试但未通过，安排一道不同题目复核。"
                            : "针对最近参考题解或卡住的专题，再安排一次独立练习。")
                        : "符合你选择的" + String.join("、", problem.tags.stream().filter(workspace.profile.topics::contains).toList()) + "专题，预计用时适合当天安排。";
                if (minutes != problem.minutes) {
                    task.reason += "用时按你在这个专题的实际记录校准为 " + minutes + " 分钟。";
                }
                usedAssessment |= !assessmentFocus.isEmpty() && problem.tags.contains(focus);
                plan.tasks.add(task);
                used.add(problem.id);
                remaining -= minutes;
                added++;
            }
        }
        plan.tasks.sort(Comparator.comparingInt(task -> task.day));
        plan.explanation = added == 0
                ? "本周剩余时段没有能容纳的候选题。可以增加可用时间、调整专题或同步更多题目。"
                : "已保留有训练记录的任务，按本周剩余时段安排 " + added + " 道新题。" + (focus.isEmpty() ? "" : "优先巩固近期需要帮助的" + focus + "专题。");
        plan.basis = new ArrayList<>(memory.stream().limit(2).toList());
        if (usedAssessment) {
            plan.basis.add(0, "最近限时短测中，" + assessmentFocus + "有提交但未通过");
            String reflection = Objects.toString(assessment.reflection, "").strip();
            if (!reflection.isEmpty()) {
                plan.basis.add(1, "学员自述的短测卡点：" + reflection.substring(0, Math.min(160, reflection.length())));
            }
        }
        return plan;
    }

    /** 同一专题的历史「实际用时 / 估计用时」中位数，用于把排期从拍脑袋变成按证据估算。 */
    private Map<String, Double> calibration(Workspace workspace) {
        Map<String, List<Double>> ratios = new HashMap<>();
        Map<String, Problem> byId = new HashMap<>();
        store.problems().forEach(problem -> byId.put(problem.id, problem));
        for (Feedback feedback : workspace.feedback) {
            Problem problem = byId.get(feedback.problemId);
            if (problem == null || problem.minutes <= 0 || feedback.minutes <= 0 || problem.tags.isEmpty()) continue;
            double ratio = (double) feedback.minutes / problem.minutes;
            ratios.computeIfAbsent(problem.tags.get(0), key -> new ArrayList<>()).add(ratio);
        }
        Map<String, Double> result = new HashMap<>();
        ratios.forEach((tag, values) -> {
            if (values.size() < 2) return;
            Collections.sort(values);
            double median = values.size() % 2 == 1
                    ? values.get(values.size() / 2)
                    : (values.get(values.size() / 2 - 1) + values.get(values.size() / 2)) / 2;
            result.put(tag, Math.max(0.5, Math.min(2.0, median)));
        });
        return result;
    }

    private int estimate(Problem problem, Map<String, Double> calibration) {
        double ratio = problem.tags.stream().map(calibration::get).filter(Objects::nonNull)
                .findFirst().orElse(1.0);
        return (int) Math.max(10, Math.min(240, Math.round(problem.minutes * ratio)));
    }

    private String applyTimeInstruction(List<Integer> budgets, String instruction) {
        Nlp.TimeIntent intent = Nlp.timeIntent(instruction);
        intent.overrides().forEach(budgets::set);
        return Nlp.echo(intent);
    }
}
