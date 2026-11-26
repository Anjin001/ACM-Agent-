package cn.xunlian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static cn.xunlian.Models.*;

@Service
public class CodeforcesClient {
    public static class Job {
        public volatile String status = "idle";
        public volatile String message = "尚未同步";
        public volatile String handle = "";
        public volatile String mode = "full";
        public volatile int pages;
        public volatile int readCount;
        private volatile boolean cancelled;
        private volatile Future<?> future;
    }
    private final ObjectMapper json;
    private final CoachStore store;
    private final TopicService topics;
    private final String baseUrl;
    private final long interval;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final ConcurrentHashMap<Long, Job> jobs = new ConcurrentHashMap<>();
    private long lastRequest;

    public CodeforcesClient(ObjectMapper json, CoachStore store, TopicService topics,
            @Value("${cf.api-base:https://codeforces.com/api/}") String baseUrl,
            @Value("${cf.interval-ms:2100}") long interval) {
        this.json = json; this.store = store; this.topics = topics;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.interval = interval;
    }
    @jakarta.annotation.PreDestroy
    public void shutdown() { executor.shutdownNow(); }
    public Job status(long userId) { return jobs.getOrDefault(userId, new Job()); }

    public synchronized Job start(long userId, String handle, String requestedMode) {
        Job existing = jobs.get(userId);
        if (existing != null && existing.status.equals("running") && existing.handle.equalsIgnoreCase(handle)) return existing;
        cancel(userId);
        SyncInfo previous = store.snapshot(userId).workspace().sync;
        Job job = new Job();
        job.handle = handle;
        job.mode = requestedMode.equals("full") || !previous.fullHistory || !previous.handle.equalsIgnoreCase(handle) ? "full" : "incremental";
        job.status = "running";
        job.message = "正在读取 CF 公开信息";
        jobs.put(userId, job);
        job.future = executor.submit(() -> sync(userId, handle, job, previous));
        return job;
    }

    public Job cancel(long userId) {
        Job job = jobs.get(userId);
        if (job == null) return new Job();
        synchronized (job) {
            if (job.status.equals("running")) {
                job.cancelled = true;
                job.status = "cancelled";
                job.message = "已取消本次同步，上次成功结果仍可使用";
                if (job.future != null) job.future.cancel(true);
            }
        }
        return job;
    }

    private void sync(long userId, String handle, Job job, SyncInfo previous) {
        try {
            JsonNode user = request("user.info?handles=" + URLEncoder.encode(handle, StandardCharsets.UTF_8)).path(0);
            Map<Long, Submission> submissions = new LinkedHashMap<>();
            Map<String, Problem> metadata = new LinkedHashMap<>();
            long latest = job.mode.equals("incremental") ? store.latestSubmission(userId, handle) : 0;
            long newest = latest;
            for (int page = 0; ; page++) {
                checkCancelled(job);
                job.message = (job.mode.equals("full") ? "正在读取完整历史" : "正在更新最近记录") + "，第 " + (page + 1) + " 页，已读取 " + job.readCount + " 条";
                JsonNode rows = request("user.status?handle=" + URLEncoder.encode(handle, StandardCharsets.UTF_8) + "&from=" + (page * 1000L + 1) + "&count=1000");
                if (!rows.isArray()) throw new IllegalStateException("CF 提交记录格式异常，请稍后重试");
                checkCancelled(job);
                long oldest = Long.MAX_VALUE;
                for (JsonNode row : rows) {
                    long id = row.path("id").asLong();
                    oldest = Math.min(oldest, id);
                    newest = Math.max(newest, id);
                    JsonNode raw = row.path("problem");
                    if (!raw.has("contestId")) continue;
                    Problem problem = toProblem(raw);
                    metadata.put(problem.id, problem);
                    submissions.put(id, new Submission(id, problem.id, row.path("verdict").asText("PENDING"), row.path("creationTimeSeconds").asLong()));
                }
                job.pages = page + 1;
                job.readCount += rows.size();
                if (rows.size() < 1000 || latest > 0 && oldest <= latest) break;
            }
            SyncInfo info = new SyncInfo();
            info.handle = handle;
            info.syncedAt = Instant.now().toString();
            info.fullHistory = job.mode.equals("full") || previous.fullHistory;
            info.lastFullSyncAt = job.mode.equals("full") ? info.syncedAt : previous.lastFullSyncAt;
            info.lastSubmissionId = newest;
            info.truncated = false;
            info.rating = user.has("rating") ? user.path("rating").asInt() : null;
            synchronized (job) {
                checkCancelled(job);
                store.saveProblems(new ArrayList<>(metadata.values()));
                store.saveSync(userId, handle, new ArrayList<>(submissions.values()), info);
                job.message = "同步完成，共保存 " + info.submissionCount + " 条提交、" + info.solvedCount + " 道已通过题目";
                job.status = "success";
            }
        } catch (Exception ex) {
            synchronized (job) {
                if (!job.cancelled) {
                    job.message = ex.getMessage() != null && ex.getMessage().startsWith("CF ") ? ex.getMessage() : "同步失败，请检查网络和 handle 后重试；已保存的数据仍可使用。";
                    job.status = "error";
                }
            }
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }

    private void checkCancelled(Job job) throws InterruptedException {
        if (job.cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedException("cancelled");
    }

    private Problem toProblem(JsonNode raw) {
        int contest = raw.path("contestId").asInt();
        String index = raw.path("index").asText();
        String id = "CF-" + contest + "-" + index;
        Problem known = store.problem(id);
        Problem problem = known == null ? new Problem() : store.copy(known, Problem.class);
        problem.id = id;
        problem.platform = "CF";
        problem.code = contest + index;
        problem.title = raw.path("name").asText(problem.code);
        problem.url = contest >= 100000 ? "https://codeforces.com/gym/" + contest + "/problem/" + index
                : "https://codeforces.com/problemset/problem/" + contest + "/" + index;
        List<String> sourceTags = new ArrayList<>();
        raw.path("tags").forEach(tag -> sourceTags.add(tag.asText()));
        if (!sourceTags.isEmpty()) {
            problem.sourceTags = sourceTags;
            problem.tags = topics.fromSourceTags(sourceTags);
        }
        if (raw.has("rating")) problem.rating = raw.path("rating").asInt();
        problem.difficulty = problem.rating == null ? "暂无评分" : problem.rating.toString();
        problem.minLevel = 800;
        problem.maxLevel = 4000;
        if (known == null) {
            problem.minutes = problem.rating == null ? 45 : Math.min(90, 30 + Math.max(0, problem.rating - 1000) / 20);
            problem.provenance = "CF 官方公开提交记录";
        }
        return problem;
    }

    private synchronized JsonNode request(String method) throws Exception {
        long delay = interval - (System.currentTimeMillis() - lastRequest);
        if (delay > 0) Thread.sleep(delay);
        lastRequest = System.currentTimeMillis();
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + method)).timeout(Duration.ofSeconds(25)).header("User-Agent", "XunlianCourseProject/0.2").GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("CF 请求返回 HTTP " + response.statusCode() + "，请稍后重试。");
        JsonNode data = json.readTree(response.body());
        if (!data.path("status").asText().equals("OK")) throw new IllegalStateException("CF 请求失败，请检查 handle 或稍后重试。");
        return data.path("result");
    }
}
