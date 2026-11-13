package cn.xunlian;

import org.springframework.stereotype.Service;

import java.util.*;

import static cn.xunlian.Models.*;

/** 能力画像：按专题与难度分桶统计等级、样本数、用时偏差和证据链。 */
@Service
public class ProfileModeler {
    private record Area(String name, List<String> tags) {}

    private static final List<Area> AREAS = List.of(
            new Area("基础实现", List.of("模拟")),
            new Area("查找与决策", List.of("二分", "双指针", "贪心")),
            new Area("搜索与图论", List.of("搜索", "图论")),
            new Area("动态规划", List.of("动态规划")),
            new Area("数据结构", List.of("数据结构")),
            new Area("数学", List.of("数学")),
            new Area("字符串", List.of("字符串")),
            new Area("计算几何", List.of("计算几何")));

    public List<AbilityArea> abilityAreas(Workspace workspace, List<Problem> problems, Set<String> attempted,
                                          Set<String> accepted) {
        Map<String, Feedback> latestByProblem = new HashMap<>();
        workspace.feedback.stream().sorted(Comparator.comparing(row -> Objects.toString(row.createdAt, "")))
                .forEach(row -> latestByProblem.put(row.problemId, row));
        List<AbilityArea> result = new ArrayList<>();
        for (Area area : AREAS) {
            List<Problem> matches = problems.stream().filter(problem -> !Collections.disjoint(area.tags(), problem.tags)).toList();
            int samples = 0;
            int independent = 0;
            int cfAttempted = 0;
            int cfAccepted = 0;
            double difficultySum = 0;
            for (Problem problem : matches) {
                Feedback latest = latestByProblem.get(problem.id);
                if (latest != null) {
                    samples++;
                    if ("independent".equals(latest.outcome)) {
                        independent++;
                        difficultySum += problem.rating == null ? 0 : difficultyContribution(problem.rating);
                    }
                }
                if ("CF".equals(problem.platform) && attempted.contains(problem.id)) {
                    cfAttempted++;
                    if (accepted.contains(problem.id)) cfAccepted++;
                }
            }
            Integer level = samples < 3 ? null : 1 + (int) Math.round(4 * (0.7 * independent + 0.3 * difficultySum) / samples);
            result.add(new AbilityArea(area.name(), area.tags(), level, samples, independent, cfAttempted, cfAccepted));
        }
        return result;
    }

    public List<PlatformCoverage> platformCoverage(List<Problem> problems, Set<String> attempted, Set<String> accepted) {
        Map<String, int[]> topics = new TreeMap<>();
        for (Problem problem : problems) {
            if (!"CF".equals(problem.platform) || !attempted.contains(problem.id)) continue;
            for (String tag : new HashSet<>(problem.tags)) {
                int[] counts = topics.computeIfAbsent(tag, key -> new int[2]);
                counts[0]++;
                if (accepted.contains(problem.id)) counts[1]++;
            }
        }
        return topics.entrySet().stream()
                .map(entry -> new PlatformCoverage(entry.getKey(), entry.getValue()[0], entry.getValue()[1]))
                .toList();
    }

    public ProfileModel build(Workspace workspace, List<Problem> problems) {
        Map<String, Problem> byId = new HashMap<>();
        problems.forEach(problem -> byId.put(problem.id, problem));

        List<Feedback> feedback = new ArrayList<>(workspace.feedback);
        feedback.sort(Comparator.comparing(item -> Objects.toString(item.createdAt, "")));

        ProfileModel model = new ProfileModel();
        model.samples = feedback.size();
        model.trainedMinutes = feedback.stream().mapToInt(item -> item.minutes).sum();
        long independent = feedback.stream().filter(item -> "independent".equals(item.outcome)).count();
        model.independentRate = feedback.isEmpty() ? 0 : (double) independent / feedback.size();

        LinkedHashSet<String> topics = new LinkedHashSet<>(workspace.profile.topics);
        feedback.forEach(item -> {
            Problem problem = byId.get(item.problemId);
            if (problem != null) topics.addAll(problem.tags);
        });

        for (String topic : topics) {
            List<Feedback> rows = feedback.stream()
                    .filter(item -> {
                        Problem problem = byId.get(item.problemId);
                        return problem != null && problem.tags.contains(topic);
                    })
                    .toList();
            model.topics.add(stat(topic, rows, byId));
        }
        model.topics.sort(Comparator
                .comparing((TopicStat item) -> item.level, Comparator.nullsLast(Comparator.<Integer>reverseOrder()))
                .thenComparingInt(item -> -item.samples));

        model.buckets = buckets(feedback, byId);
        List<AssessmentService.Assessment> assessments = new ArrayList<>();
        if (workspace.assessment != null) assessments.add(workspace.assessment);
        assessments.addAll(workspace.assessmentHistory);
        assessments.stream().filter(test -> !test.results.isEmpty()).limit(3).forEach(test -> {
            for (var result : test.results) {
                Problem problem = byId.get(result.problemId());
                model.timedEvidence.add(new TimedEvidence(result.problemId(),
                        problem == null ? result.problemId() : problem.title, result.status(),
                        result.submissions(), result.firstAcceptedSeconds(), test.endedAt == null ? test.deadline : test.endedAt));
            }
        });

        for (TopicStat topic : model.topics) {
            if (topic.samples < 2) continue;
            if (topic.independentRate >= 0.75) {
                model.strengths.add(topic.name + "：独立完成率 " + percent(topic.independentRate));
            } else if (topic.independentRate <= 0.5) {
                model.weaknesses.add(topic.name + "：" + topic.samples + " 次记录中 " + topic.independent + " 次独立完成");
            }
        }
        model.summary = summary(model);
        model.basis = new ArrayList<>(model.weaknesses.stream().limit(2).toList());
        if (workspace.profile.goal != null && !workspace.profile.goal.isBlank()) {
            model.basis.add(0, "训练目标：" + workspace.profile.goal.trim());
        }
        return model;
    }

    private TopicStat stat(String topic, List<Feedback> rows, Map<String, Problem> byId) {
        TopicStat stat = new TopicStat();
        stat.name = topic;
        stat.samples = rows.size();
        stat.independent = (int) rows.stream().filter(item -> "independent".equals(item.outcome)).count();
        stat.editorial = (int) rows.stream().filter(item -> "editorial".equals(item.outcome)).count();
        stat.stuck = (int) rows.stream().filter(item -> "stuck".equals(item.outcome)).count();
        stat.independentRate = rows.isEmpty() ? 0 : (double) stat.independent / rows.size();

        List<Problem> related = rows.stream().map(item -> byId.get(item.problemId)).filter(Objects::nonNull).toList();
        stat.avgMinutes = (int) Math.round(rows.stream().mapToInt(item -> item.minutes).average().orElse(0));
        stat.avgEstimate = (int) Math.round(related.stream().mapToInt(problem -> problem.minutes).average().orElse(0));
        stat.bias = stat.avgMinutes - stat.avgEstimate;
        OptionalDouble rating = related.stream().filter(problem -> problem.rating != null).mapToInt(problem -> problem.rating).average();
        stat.avgRating = rating.isPresent() ? (int) Math.round(rating.getAsDouble()) : null;

        if (!rows.isEmpty()) {
            double difficulty = stat.avgRating == null ? 0.45 : difficultyContribution(stat.avgRating);
            // 样本少时压低等级，避免只做对一道题就显示高等级。
            double confidence = Math.min(1.0, 0.4 + 0.2 * stat.samples);
            double score = (0.65 * stat.independentRate + 0.35 * difficulty) * confidence;
            stat.level = (int) Math.max(1, Math.min(5, Math.round(1 + 4 * score)));
        }

        List<Feedback> recent = new ArrayList<>(rows);
        recent.sort(Comparator.comparing((Feedback item) -> Objects.toString(item.createdAt, "")).reversed());
        for (Feedback item : recent.stream().limit(3).toList()) {
            Problem problem = byId.get(item.problemId);
            Evidence evidence = new Evidence();
            evidence.problemId = item.problemId;
            evidence.code = problem == null ? item.problemId : problem.code;
            evidence.title = problem == null ? item.problemId : problem.title;
            evidence.outcome = item.outcome;
            evidence.minutes = item.minutes;
            evidence.estimate = problem == null ? item.minutes : problem.minutes;
            evidence.date = item.createdAt;
            if (problem != null) evidence.tags = problem.tags;
            stat.evidence.add(evidence);
        }
        return stat;
    }

    private List<BucketStat> buckets(List<Feedback> feedback, Map<String, Problem> byId) {
        LinkedHashMap<String, BucketStat> map = new LinkedHashMap<>();
        for (Feedback item : feedback) {
            Problem problem = byId.get(item.problemId);
            if (problem == null) continue;
            String key;
            Integer min = null;
            Integer max = null;
            if (problem.rating == null) {
                key = "洛谷精选";
            } else {
                int base = (problem.rating / 200) * 200;
                min = base;
                max = base + 199;
                key = base + "–" + max;
            }
            BucketStat bucket = map.computeIfAbsent(key, label -> {
                BucketStat created = new BucketStat();
                created.label = label;
                return created;
            });
            bucket.minRating = min;
            bucket.maxRating = max;
            bucket.attempts++;
            if ("independent".equals(item.outcome)) bucket.independent++;
        }
        List<BucketStat> result = new ArrayList<>(map.values());
        result.forEach(bucket -> bucket.rate = bucket.attempts == 0 ? 0 : (double) bucket.independent / bucket.attempts);
        result.sort(Comparator.comparing(bucket -> bucket.minRating == null ? 9999 : bucket.minRating));
        return result;
    }

    private String summary(ProfileModel model) {
        if (model.samples == 0) {
            return "还没有训练记录。完成一次练习并在任务里写下复盘，这里会按专题给出等级、样本数和证据。";
        }
        StringBuilder text = new StringBuilder();
        text.append(model.samples).append(" 次记录、").append(model.trainedMinutes).append(" 分钟训练，独立完成率 ")
                .append(percent(model.independentRate)).append("。");
        if (!model.weaknesses.isEmpty()) {
            text.append("最需要补强的是").append(model.weaknesses.get(0).split("：")[0]).append("。");
        } else if (!model.strengths.isEmpty()) {
            text.append("目前").append(model.strengths.get(0).split("：")[0]).append("表现稳定。");
        }
        return text.toString();
    }

    private String percent(double value) {
        return Math.round(value * 100) + "%";
    }

    private double difficultyContribution(int rating) {
        double scaled = Math.max(0, rating) / 1600.0;
        return scaled / (1 + scaled);
    }

}
