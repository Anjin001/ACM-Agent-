package cn.xunlian;

import org.springframework.stereotype.Service;
import java.util.*;
import static cn.xunlian.Models.*;

@Service
public class ProblemLibrary {
    private final CoachStore store;
    private final TopicService topics;

    public ProblemLibrary(CoachStore store, TopicService topics) { this.store = store; this.topics = topics; }

    public ProblemPage search(long userId, String query, String platform, String topic, String completion, int minRating, int maxRating, int page, int pageSize, boolean acceptedOnly) {
        Workspace workspace = store.snapshot(userId).workspace();
        Map<String, long[]> accepted = store.acceptedStats(userId, workspace.profile.handle);
        Set<String> completed = store.excluded(userId, workspace);
        Set<String> attempted = store.attempted(userId, workspace.profile.handle);
        workspace.feedback.forEach(feedback -> attempted.add(feedback.problemId));
        String needle = query.trim().toLowerCase(Locale.ROOT);
        Map<String, Problem> candidates = new LinkedHashMap<>();
        store.problems().forEach(problem -> candidates.put(problem.id, problem));
        if (acceptedOnly) accepted.keySet().forEach(id -> {
            if (!candidates.containsKey(id)) {
                Problem historical = historicalProblem(id);
                if (historical != null) candidates.put(id, historical);
            }
        });
        List<Problem> matching = candidates.values().stream()
                .filter(problem -> !acceptedOnly || accepted.containsKey(problem.id))
                .filter(problem -> platform.equals("all") || problem.platform.equals(platform))
                .filter(problem -> topic.equals("all") || topics.matches(problem, topic))
                .filter(problem -> minRating == 0 || problem.rating != null && problem.rating >= minRating)
                .filter(problem -> maxRating == 0 || problem.rating != null && problem.rating <= maxRating)
                .filter(problem -> needle.isEmpty() || (problem.code + " " + problem.title + " " + String.join(" ", problem.tags) + " " + String.join(" ", problem.sourceTags)).toLowerCase(Locale.ROOT).contains(needle))
                .filter(problem -> switch (completion) {
                    case "solved" -> completed.contains(problem.id);
                    case "unsolved" -> !completed.contains(problem.id);
                    case "attempted" -> attempted.contains(problem.id) && !completed.contains(problem.id);
                    default -> true;
                })
                .sorted(acceptedOnly
                        ? Comparator.comparingLong((Problem problem) -> accepted.get(problem.id)[1]).reversed().thenComparing(problem -> problem.id)
                        : Comparator.comparingInt((Problem problem) -> problem.rating == null ? 5000 : problem.rating).thenComparing(problem -> problem.id))
                .toList();
        int size = Math.max(1, Math.min(100, pageSize));
        int pages = Math.max(1, (matching.size() + size - 1) / size);
        int selectedPage = Math.max(1, Math.min(pages, page));
        int start = (selectedPage - 1) * size;
        List<ProblemRow> rows = matching.subList(start, Math.min(start + size, matching.size())).stream().map(problem -> {
            long[] stats = accepted.get(problem.id);
            String status = stats != null ? "cf" : completed.contains(problem.id) ? "manual" : attempted.contains(problem.id) ? "attempted" : "todo";
            return new ProblemRow(problem, status, stats == null ? null : stats[0], stats == null ? null : stats[1], stats == null ? 0 : (int) stats[2]);
        }).toList();
        return new ProblemPage(rows, matching.size(), selectedPage, size, pages);
    }

    private Problem historicalProblem(String id) {
        var match = java.util.regex.Pattern.compile("^CF-(\\d+)-([A-Za-z0-9]+)$").matcher(id);
        if (!match.matches()) return null;
        String contest = match.group(1), index = match.group(2);
        Problem problem = new Problem();
        problem.id = id;
        problem.platform = "CF";
        problem.code = contest + index;
        problem.title = "历史题目（待刷新详情）";
        problem.url = Long.parseLong(contest) >= 100000
                ? "https://codeforces.com/gym/" + contest + "/problem/" + index
                : "https://codeforces.com/problemset/problem/" + contest + "/" + index;
        problem.difficulty = "暂无评分";
        problem.provenance = "已保存的 CF 通过记录；完整同步后补充题目详情";
        return problem;
    }
}
