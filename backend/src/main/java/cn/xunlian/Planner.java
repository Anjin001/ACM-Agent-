package cn.xunlian;

import org.springframework.stereotype.Service;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.regex.*;
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
        applyTimeInstruction(plan.dailyMinutes, instruction);
        Set<String> used = new HashSet<>();
        plan.tasks.forEach(task -> used.add(task.problemId));
        String focus = workspace.feedback.stream()
                .filter(feedback -> !feedback.outcome.equals("independent"))
                .reduce((first, second) -> second)
                .flatMap(feedback -> store.problems().stream().filter(problem -> problem.id.equals(feedback.problemId)).findFirst())
                .map(problem -> problem.tags.isEmpty() ? "" : problem.tags.get(0)).orElse("");
        List<Problem> available = new ArrayList<>(candidates(userId, workspace));
        available.sort(Comparator.comparingInt((Problem problem) -> problem.tags.contains(focus) ? 0 : 1)
                .thenComparingInt(problem -> problem.minutes));
        int firstDay = today.getDayOfWeek().getValue() - 1;
        int added = 0;
        for (int day = firstDay; day < 7; day++) {
            final int taskDay = day;
            int usedMinutes = plan.tasks.stream().filter(task -> task.day == taskDay).mapToInt(task -> task.minutes).sum();
            int remaining = plan.dailyMinutes.get(day) - usedMinutes;
            for (Problem problem : available) {
                if (used.contains(problem.id) || problem.minutes > remaining || added >= 6) continue;
                Task task = new Task();
                task.id = UUID.randomUUID().toString();
                task.problemId = problem.id;
                task.day = day;
                task.minutes = problem.minutes;
                task.reason = problem.tags.contains(focus)
                        ? "针对最近参考题解或卡住的专题，再安排一次独立练习。"
                        : "符合你选择的" + String.join("、", problem.tags.stream().filter(workspace.profile.topics::contains).toList()) + "专题，预计用时适合当天安排。";
                plan.tasks.add(task);
                used.add(problem.id);
                remaining -= task.minutes;
                added++;
            }
        }
        plan.tasks.sort(Comparator.comparingInt(task -> task.day));
        plan.explanation = added == 0
                ? "本周剩余时段没有能容纳的候选题。可以增加可用时间、调整专题或同步更多题目。"
                : "已保留有训练记录的任务，按本周剩余时段安排 " + added + " 道新题。" + (focus.isEmpty() ? "" : "优先巩固近期需要帮助的" + focus + "专题。");
        return plan;
    }

    private void applyTimeInstruction(List<Integer> budgets, String instruction) {
        if (instruction == null) return;
        Matcher daily = Pattern.compile("每天[^0-9]{0,8}([0-9]{1,3})\\s*分钟").matcher(instruction);
        if (daily.find()) {
            int minutes = Math.min(480, Integer.parseInt(daily.group(1)));
            Collections.fill(budgets, minutes);
        }
        Matcher matcher = Pattern.compile("周([一二三四五六日天])[^0-9周]{0,12}([0-9]{1,3})\\s*分钟").matcher(instruction);
        while (matcher.find()) {
            int day = "一二三四五六日".indexOf(matcher.group(1).replace("天", "日"));
            budgets.set(day, Math.min(480, Integer.parseInt(matcher.group(2))));
        }
        for (int day = 0; day < 7; day++) {
            String name = "周" + "一二三四五六日".charAt(day);
            if (instruction.matches("(?s).*" + name + "(?:没时间|没有时间|不能练|不练).*")) budgets.set(day, 0);
        }
    }
}
