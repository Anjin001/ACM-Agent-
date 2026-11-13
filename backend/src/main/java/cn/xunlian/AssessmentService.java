package cn.xunlian;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;

import static cn.xunlian.Models.*;

@Service
public class AssessmentService {
    private final CoachStore store;
    private final Planner planner;
    private final MemoryStore memory;

    public AssessmentService(CoachStore store, Planner planner, MemoryStore memory) {
        this.store = store;
        this.planner = planner;
        this.memory = memory;
    }

    public static class Assessment {
        public String id;
        public String handle;
        public long startedAt;
        public long deadline;
        public Long endedAt;
        public boolean cancelled;
        public String reflection = "";
        public List<String> problemIds = new ArrayList<>();
        public List<Result> results = new ArrayList<>();
    }

    public record Result(String problemId, String status, int submissions, Long firstAcceptedSeconds) {}
    public record Reflection(String note) {}
    public record State(Assessment current, List<Assessment> history, long serverTime) {}

    public State state(long userId) {
        Workspace workspace = store.snapshot(userId).workspace();
        return new State(workspace.assessment, workspace.assessmentHistory, now());
    }

    @Transactional
    public State start(long userId) {
        Snapshot snapshot = store.snapshot(userId);
        Workspace workspace = snapshot.workspace();
        if (workspace.assessment != null && workspace.assessment.results.isEmpty() && !workspace.assessment.cancelled) {
            throw conflict("请先结算当前短测");
        }
        if (workspace.profile.handle.isBlank() || !workspace.profile.handle.equalsIgnoreCase(workspace.sync.handle)
                || workspace.sync.syncedAt == null) throw conflict("请先关联并同步 Codeforces 账号");
        Set<String> seen = store.attempted(userId, workspace.profile.handle);
        workspace.assessmentHistory.forEach(test -> seen.addAll(test.problemIds));
        if (workspace.assessment != null) seen.addAll(workspace.assessment.problemIds);
        List<String> candidates = planner.candidates(userId, workspace).stream()
                .filter(p -> "CF".equals(p.platform) && !seen.contains(p.id))
                .sorted(Comparator.comparingInt((Problem p) -> Math.abs(Objects.requireNonNullElse(p.rating, 0) - workspace.profile.level))
                        .thenComparing(p -> p.id))
                .map(p -> p.id).limit(3).toList();
        if (candidates.size() != 3) throw conflict("当前难度和专题内不足 3 道未见题，请调整训练设置");
        if (workspace.assessment != null) {
            workspace.assessmentHistory.add(0, workspace.assessment);
            if (workspace.assessmentHistory.size() > 12) workspace.assessmentHistory.remove(12);
        }
        Assessment assessment = new Assessment();
        assessment.id = UUID.randomUUID().toString();
        assessment.handle = workspace.profile.handle;
        assessment.startedAt = now();
        assessment.deadline = assessment.startedAt + 3600;
        assessment.problemIds = candidates;
        workspace.assessment = assessment;
        store.save(userId, snapshot.revision(), workspace);
        return new State(assessment, workspace.assessmentHistory, now());
    }

    @Transactional
    public State end(long userId, String id) {
        Snapshot snapshot = store.snapshot(userId);
        Workspace workspace = snapshot.workspace();
        Assessment assessment = require(workspace, id);
        if (assessment.endedAt == null && assessment.results.isEmpty()) {
            assessment.endedAt = Math.min(now(), assessment.deadline);
            store.save(userId, snapshot.revision(), workspace);
        }
        return new State(assessment, workspace.assessmentHistory, now());
    }

    @Transactional
    public State cancel(long userId, String id) {
        Snapshot snapshot = store.snapshot(userId);
        Workspace workspace = snapshot.workspace();
        Assessment assessment = require(workspace, id);
        if (!assessment.cancelled && assessment.results.isEmpty()) {
            assessment.cancelled = true;
            if (assessment.endedAt == null) assessment.endedAt = Math.min(now(), assessment.deadline);
            store.save(userId, snapshot.revision(), workspace);
        }
        return new State(assessment, workspace.assessmentHistory, now());
    }

    @Transactional
    public State settle(long userId, String id) {
        Snapshot snapshot = store.snapshot(userId);
        Workspace workspace = snapshot.workspace();
        Assessment assessment = require(workspace, id);
        if (assessment.cancelled) throw conflict("这场短测已放弃");
        if (!assessment.results.isEmpty()) return new State(assessment, workspace.assessmentHistory, now());
        long end = assessment.endedAt == null ? assessment.deadline : assessment.endedAt;
        if (now() < end) throw conflict("请先结束短测");
        SyncInfo sync = workspace.sync;
        if (!assessment.handle.equalsIgnoreCase(sync.handle) || sync.syncedAt == null
                || sync.fetchStartedAt == null || sync.fetchStartedAt < end) {
            throw conflict("请先同步测评使用的 Codeforces 账号，再结算");
        }
        if (sync.truncated && (sync.coverageStartsAt == null || sync.coverageStartsAt >= assessment.startedAt)) {
            throw conflict("同步窗口未覆盖完整短测，暂不能确认结果");
        }
        List<Submission> submissions = store.submissionsBetween(userId, assessment.handle, assessment.startedAt, end);
        for (String idOfProblem : assessment.problemIds) {
            List<Submission> rows = submissions.stream().filter(row -> row.problemId().equals(idOfProblem)).toList();
            OptionalLong accepted = rows.stream().filter(row -> "OK".equals(row.verdict()))
                    .mapToLong(Submission::createdAt).min();
            if (accepted.isEmpty() && rows.stream().anyMatch(row -> Set.of("TESTING", "SUBMITTED", "PENDING", "").contains(row.verdict()))) {
                throw conflict("还有提交在等待判题，请稍后重新同步");
            }
            String status = accepted.isPresent() ? "accepted" : rows.isEmpty() ? "unsubmitted" : "attempted";
            assessment.results.add(new Result(idOfProblem, status, rows.size(),
                    accepted.isPresent() ? accepted.getAsLong() - assessment.startedAt : null));
        }
        assessment.endedAt = end;
        long solved = assessment.results.stream().filter(result -> "accepted".equals(result.status())).count();
        memory.event(userId, "assessment", "限时短测通过 " + solved + "/3 道题", null);
        store.save(userId, snapshot.revision(), workspace);
        return new State(assessment, workspace.assessmentHistory, now());
    }

    @Transactional
    public State reflect(long userId, String id, Reflection reflection) {
        Snapshot snapshot = store.snapshot(userId);
        Workspace workspace = snapshot.workspace();
        Assessment assessment = require(workspace, id);
        if (assessment.results.isEmpty()) throw conflict("请先结算短测");
        String note = Objects.toString(reflection.note(), "").strip();
        if (note.length() > 600) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "短测复盘最多 600 个字符");
        assessment.reflection = note;
        store.save(userId, snapshot.revision(), workspace);
        return new State(assessment, workspace.assessmentHistory, now());
    }

    private Assessment require(Workspace workspace, String id) {
        if (workspace.assessment == null || !workspace.assessment.id.equals(id)) throw conflict("短测已变化，请刷新");
        return workspace.assessment;
    }

    private static long now() { return Instant.now().getEpochSecond(); }
    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
