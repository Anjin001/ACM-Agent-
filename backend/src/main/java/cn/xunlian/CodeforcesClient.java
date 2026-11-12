package cn.xunlian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    }
    private final ObjectMapper json;
    private final CoachStore store;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final ConcurrentHashMap<Long, Job> jobs = new ConcurrentHashMap<>();
    private long lastRequest;

    public CodeforcesClient(ObjectMapper json, CoachStore store) { this.json = json; this.store = store; }
    @jakarta.annotation.PreDestroy
    public void shutdown() { executor.shutdownNow(); }
    public Job status(long userId) { return jobs.getOrDefault(userId, new Job()); }

    public synchronized Job start(long userId, String handle) {
        Job existing = jobs.get(userId);
        if (existing != null && existing.status.equals("running")) return existing;
        Job job = new Job();
        job.status = "running";
        job.message = "正在读取 CF 公开信息";
        jobs.put(userId, job);
        executor.submit(() -> sync(userId, handle, job));
        return job;
    }

    private void sync(long userId, String handle, Job job) {
        try {
            JsonNode user = request("user.info?handles=" + URLEncoder.encode(handle, StandardCharsets.UTF_8)).path(0);
            Map<Long, Submission> submissions = new LinkedHashMap<>();
            boolean truncated = false;
            long fetchStartedAt = Instant.now().getEpochSecond();
            // Bound a first sync to 10,000 recent records and report its coverage explicitly.
            for (int page = 0; page < 10; page++) {
                job.message = "正在读取提交记录，第 " + (page + 1) + " 页";
                JsonNode rows = request("user.status?handle=" + URLEncoder.encode(handle, StandardCharsets.UTF_8) + "&from=" + (page * 1000 + 1) + "&count=1000");
                for (JsonNode row : rows) {
                    JsonNode problem = row.path("problem");
                    if (!problem.has("contestId")) continue;
                    long id = row.path("id").asLong();
                    submissions.put(id, new Submission(id, "CF-" + problem.path("contestId").asInt() + "-" + problem.path("index").asText(), row.path("verdict").asText("PENDING"), row.path("creationTimeSeconds").asLong()));
                }
                if (rows.size() < 1000) break;
                if (page == 9) truncated = true;
            }
            SyncInfo info = new SyncInfo();
            info.handle = handle;
            info.syncedAt = Instant.now().toString();
            info.fetchStartedAt = fetchStartedAt;
            info.coverageStartsAt = submissions.values().stream().map(Submission::createdAt).min(Long::compareTo).orElse(null);
            info.submissionCount = submissions.size();
            info.solvedCount = (int) submissions.values().stream().filter(row -> row.verdict().equals("OK")).map(Submission::problemId).distinct().count();
            info.truncated = truncated;
            info.rating = user.has("rating") ? user.path("rating").asInt() : null;
            store.saveSync(userId, handle, new ArrayList<>(submissions.values()), info);
            job.message = "已同步 " + submissions.size() + " 条记录，覆盖 " + info.solvedCount + " 道已通过题目" + (truncated ? "（最近 10000 条范围）" : "");
            job.status = "success";
        } catch (Exception ex) {
            job.message = ex.getMessage() != null && ex.getMessage().startsWith("CF ") ? ex.getMessage() : "同步失败，请检查网络和 handle 后重试；已保存的数据仍可使用。";
            job.status = "error";
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }

    private synchronized JsonNode request(String method) throws Exception {
        long delay = 2100 - (System.currentTimeMillis() - lastRequest);
        if (delay > 0) Thread.sleep(delay);
        lastRequest = System.currentTimeMillis();
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://codeforces.com/api/" + method)).timeout(Duration.ofSeconds(25)).header("User-Agent", "XunlianCourseProject/0.1").GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("CF 请求返回 HTTP " + response.statusCode() + "，请稍后重试。");
        JsonNode data = json.readTree(response.body());
        if (!data.path("status").asText().equals("OK")) throw new IllegalStateException("CF 请求失败，请检查 handle 或稍后重试。");
        return data.path("result");
    }
}
