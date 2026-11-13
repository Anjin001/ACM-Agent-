package cn.xunlian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class CoachFlowTest {
    // 每次运行独立的 SQLite 库，验证的是生产用的同一种方言。
    static final String DB_URL = "jdbc:sqlite:" + Path.of("target", "test-" + UUID.randomUUID() + ".db").toAbsolutePath()
            + "?journal_mode=WAL&busy_timeout=5000";

    private static final Path AI_SETTINGS = Path.of("target", "ai-settings-test-" + UUID.randomUUID());

    @DynamicPropertySource
    static void sqlite(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DB_URL);
        registry.add("xunlian.ai-settings-directory", () -> AI_SETTINGS.toString());
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired CoachStore store;

    @Test
    void localModelSettingsArePrivateAndUsedByBothAiFlows() throws Exception {
        MockHttpSession owner = register();
        MockHttpSession other = register();
        String key = "test-private-model-key";
        String route = "/api/settings/ai";
        HttpServer provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext("/v1/chat/completions", exchange -> {
            try (exchange) {
                String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                JsonNode request = json.readTree(raw);
                if (!("Bearer " + key).equals(exchange.getRequestHeaders().getFirst("Authorization"))
                        || raw.contains(key) || !"personal-model".equals(request.path("model").asText())) {
                    exchange.sendResponseHeaders(401, -1);
                    return;
                }
                JsonNode context = json.readTree(request.path("messages").path(1).path("content").asText());
                Object answer;
                if (context.has("filesScanned")) {
                    if (raw.contains("raw_only_marker")) {
                        exchange.sendResponseHeaders(400, -1);
                        return;
                    }
                    answer = Map.of("summary", "整个目录的统计已汇总", "level", "实现覆盖待验证");
                } else if (context.has("samples")) {
                    answer = Map.of("summary", "看到了二分实现", "level", "有实现经验，独立性待确认",
                            "gaps", List.of("下一步验证边界"), "nextQuestion", "这是你独立写的吗？",
                            "evidence", List.of(
                                    Map.of("topic", "二分", "file", "week1/main.cpp", "line", 1, "observation", "二分样例"),
                                    Map.of("topic", "伪造", "file", "../foreign.cpp", "line", 1, "observation", "应丢弃")));
                } else if (context.has("userMessage")) {
                    answer = Map.of("answer", "下一步做一道陌生二分变式。");
                } else if (context.has("files")) {
                    answer = Map.of("issues", List.of(Map.of("file", "main.cpp", "line", 1,
                            "kind", "实现", "message", "本地测试模型的审查结果")));
                } else {
                    JsonNode task = context.path("baselinePlan").path("tasks").path(0);
                    answer = Map.of("reason", "本地测试模型的计划", "tasks", List.of(Map.of(
                            "problemId", task.path("problemId").asText(), "day", task.path("day").asInt(),
                            "minutes", task.path("minutes").asInt())));
                }
                byte[] reply = json.writeValueAsBytes(Map.of("choices", List.of(Map.of("message",
                        Map.of("content", json.writeValueAsString(answer))))));
                exchange.sendResponseHeaders(200, reply.length);
                exchange.getResponseBody().write(reply);
            }
        });
        provider.start();
        try {
            String baseUrl = "http://127.0.0.1:" + provider.getAddress().getPort() + "/v1";
            Map<String, String> settings = Map.of("baseUrl", baseUrl, "model", "personal-model", "apiKey", key);
            mvc.perform(put(route).session(owner).with(request -> {
                        request.setRemoteAddr("192.0.2.1");
                        return request;
                    }).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(settings)))
                    .andExpect(status().isForbidden());

            JsonNode saved = send(owner, "PUT", route, settings);
            assertThat(saved.path("aiConfigured").asBoolean()).isTrue();
            assertThat(saved.path("aiSettings").path("hasLocalKey").asBoolean()).isTrue();
            assertThat(saved.toString()).doesNotContain(key);
            assertThat(getJson(other, "/api/state").path("aiSettings").path("hasLocalKey").asBoolean()).isFalse();
            try (var files = Files.list(AI_SETTINGS)) {
                for (Path file : files.toList()) {
                    assertThat(new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1)).doesNotContain(key);
                }
            }

            JsonNode plan = seedPlan(owner);
            JsonNode proposal = send(owner, "POST", "/api/plan/proposal",
                    Map.of("revision", plan.path("revision").asLong(), "instruction", "每天90分钟"));
            assertThat(proposal.path("workspace").path("proposal").path("engine").asText()).isEqualTo("model");
            JsonNode review = send(owner, "POST", "/api/code/review",
                    Map.of("files", List.of(Map.of("name", "main.cpp", "content", "int main() {}"))));
            assertThat(review.path("engine").asText()).isEqualTo("model");
            assertThat(review.path("findings").toString()).contains("本地测试模型的审查结果");

            String workspaceRoute = "/api/code/workspace";
            String source = "int main() { return std::lower_bound(a, a+n, x)-a; }";
            mvc.perform(post(workspaceRoute).session(owner).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("folder", "practice", "files",
                            List.of(Map.of("name", "../outside.cpp", "content", source))))))
                    .andExpect(status().isBadRequest());
            JsonNode workspace = send(owner, "POST", workspaceRoute, Map.of("folder", "practice", "useModel", true,
                    "files", List.of(Map.of("name", "week1/main.cpp", "content", source))));
            assertThat(workspace.path("engine").asText()).isEqualTo("model");
            assertThat(workspace.path("evidence").toString()).contains("week1/main.cpp").doesNotContain("foreign.cpp");
            assertThat(workspace.toString()).doesNotContain(source);
            assertThat(getJson(other, workspaceRoute).path("report").isNull()).isTrue();

            Map<String, Object> message = Map.of("workspaceId", workspace.path("id").asText(),
                    "revision", workspace.path("revision").asLong(), "question", "第一周的代码是我独立写的",
                    "useModel", true, "remember", true);
            JsonNode chatted = send(owner, "POST", workspaceRoute + "/chat", message);
            assertThat(chatted.path("messages").path(1).path("engine").asText()).isEqualTo("model");
            assertThat(getJson(owner, workspaceRoute).path("report").path("messages").size()).isEqualTo(2);
            assertThat(getJson(owner, "/api/memory").toString()).contains("第一周的代码是我独立写的");
            mvc.perform(post(workspaceRoute + "/chat").session(owner).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(message))).andExpect(status().isConflict());
            mvc.perform(delete(workspaceRoute + "/" + chatted.path("id").asText())
                    .param("revision", chatted.path("revision").asText()).session(owner)).andExpect(status().isOk());
            assertThat(getJson(owner, workspaceRoute).path("report").isNull()).isTrue();

            String scanRoot = workspaceRoute + "/scans";
            JsonNode scan = send(owner, "POST", scanRoot, Map.of("folder", "model-folder", "fileCount", 1, "useModel", true));
            String scanRoute = scanRoot + "/" + scan.path("id").asText();
            send(owner, "POST", scanRoute + "/batches", Map.of("index", 0, "segments", List.of(
                    new CodeWorkspaceService.Segment("main.cpp", "int raw_only_marker = gcd(a,b);", 1, true, false))));
            JsonNode fullReport = json.readTree(mvc.perform(post(scanRoute + "/finish").session(owner))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
            assertThat(fullReport.path("engine").asText()).isEqualTo("model");
            assertThat(fullReport.path("summary").asText()).contains("整个目录的统计已汇总");

            mvc.perform(delete(route).session(other)).andExpect(status().isOk());
            assertThat(getJson(owner, "/api/state").path("aiSettings").path("hasLocalKey").asBoolean()).isTrue();
            mvc.perform(delete(route).session(owner)).andExpect(status().isOk());
            assertThat(getJson(owner, "/api/state").path("aiSettings").path("hasLocalKey").asBoolean()).isFalse();
        } finally {
            provider.stop(0);
        }
    }

    @Test
    void scansMoreThanAThousandFilesAndRemembersEvidenceFromTheEnd() throws Exception {
        MockHttpSession session = register();
        String root = "/api/code/workspace/scans";
        int total = 1202;
        JsonNode scan = send(session, "POST", root, Map.of("folder", "practice", "fileCount", total));
        String route = root + "/" + scan.path("id").asText();
        mvc.perform(post(route + "/finish").session(session)).andExpect(status().isConflict());
        for (int offset = 0, batch = 0; offset < 1200; offset += 60, batch++) {
            List<CodeWorkspaceService.Segment> files = new ArrayList<>();
            for (int number = offset; number < offset + 60; number++) {
                String source = number == 1199 ? "int mid=(l+r)/2;" : "int main() {}";
                files.add(new CodeWorkspaceService.Segment("week/problem" + number + ".cpp", source, 1, true, false));
            }
            Map<String, Object> body = Map.of("index", batch, "segments", files);
            JsonNode progress = send(session, "POST", route + "/batches", body);
            assertThat(progress.path("filesProcessed").asInt()).isEqualTo(offset + 60);
            if (batch == 0) {
                assertThat(send(session, "POST", route + "/batches", body).path("filesProcessed").asInt()).isEqualTo(60);
            }
        }
        String prefix = "// long file\n".repeat(1000);
        List<CodeWorkspaceService.Segment> tail = List.of(
                new CodeWorkspaceService.Segment("week/tail.cpp", prefix, 1, false, false),
                new CodeWorkspaceService.Segment("week/tail.cpp", "int mid=(l+r)/2;\nint x = gcd(a,b);", 1001, true, false),
                new CodeWorkspaceService.Segment("empty.cpp", "", 1, true, true));
        assertThat(send(session, "POST", route + "/batches", Map.of("index", 20, "segments", tail))
                .path("filesProcessed").asInt()).isEqualTo(total);
        JsonNode report = json.readTree(mvc.perform(post(route + "/finish").session(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(report.path("scannedFiles").asInt()).isEqualTo(1201);
        assertThat(report.path("skippedFiles").asInt()).isEqualTo(1);
        assertThat(report.path("files").size()).isEqualTo(1201);
        assertThat(report.path("recurringIssues").path(0).path("files").asInt()).isEqualTo(2);
        assertThat(report.path("evidence").toString()).contains("week/tail.cpp").contains("1002");
        JsonNode savedMemory = getJson(session, "/api/memory");
        assertThat(savedMemory.path("observations").toString())
                .contains("code_folder", "观察到专题：数学", "多文件出现待复核问题：边界", "week/tail.cpp:1002");

        String observationId = "";
        for (JsonNode item : savedMemory.path("observations")) {
            if (item.path("statement").asText().contains("观察到专题：数学")) observationId = item.path("id").asText();
        }
        assertThat(observationId).isNotBlank();
        mvc.perform(patch("/api/memory/" + observationId).session(session).contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":false}")).andExpect(status().isOk());
        JsonNode rescanned = send(session, "POST", root, Map.of("folder", "practice", "fileCount", 1));
        String rescanRoute = root + "/" + rescanned.path("id").asText();
        send(session, "POST", rescanRoute + "/batches", Map.of("index", 0, "segments", List.of(
                new CodeWorkspaceService.Segment("week/recheck.cpp", "int x = gcd(a,b);", 1, true, false))));
        mvc.perform(post(rescanRoute + "/finish").session(session)).andExpect(status().isOk());
        JsonNode after = getJson(session, "/api/memory").path("observations");
        int matches = 0;
        for (JsonNode item : after) {
            if (!item.path("statement").asText().contains("观察到专题：数学")) continue;
            matches++;
            assertThat(item.path("active").asBoolean()).isFalse();
            assertThat(item.path("detail").asText()).contains("week/recheck.cpp:1");
        }
        assertThat(matches).isEqualTo(1);
    }

    @Test
    void browsesOnlyTheOpenedLocalFolder(@org.junit.jupiter.api.io.TempDir Path root) throws Exception {
        MockHttpSession session = register();
        MockHttpSession other = register();
        Files.createDirectories(root.resolve("solutions"));
        Files.writeString(root.resolve("solutions/main.cpp"), "// local\nint gcd(int a, int b);\nint result = gcd(2, 4);");
        Files.createDirectories(root.resolve("build"));
        Files.writeString(root.resolve("build/ignored.cpp"), "int ignored;");
        JsonNode opened = send(session, "POST", "/api/code/local", Map.of("path", root.toString()));
        assertThat(opened.path("files").asInt()).isEqualTo(1);
        JsonNode hit = json.readTree(mvc.perform(get("/api/code/local/search").session(session)
                .param("q", "gcd").param("content", "true")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(hit.path(0).path("file").asText()).isEqualTo("solutions/main.cpp");
        assertThat(hit.path(0).path("line").asInt()).isEqualTo(2);
        assertThat(hit.path(1).path("line").asInt()).isEqualTo(3);
        mvc.perform(get("/api/code/local/file").session(session).param("file", "solutions/main.cpp").param("line", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.lines[0]").value("int gcd(int a, int b);"));
        mvc.perform(get("/api/code/local/file").session(session).param("file", "../main.cpp"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/code/local/file").session(other).param("file", "solutions/main.cpp"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/code/local/search").session(session).with(request -> {
            request.setRemoteAddr("192.0.2.5");
            return request;
        })).andExpect(status().isForbidden());
    }

    @Test
    void assessmentChecksTimeAccountVerdictAndKeepsHistory() throws Exception {
        MockHttpSession session = register();
        JsonNode initial = getJson(session, "/api/state");
        var profile = (com.fasterxml.jackson.databind.node.ObjectNode) initial.path("workspace").path("profile").deepCopy();
        profile.put("handle", "sample_user");
        profile.set("dailyMinutes", json.valueToTree(List.of(120,120,120,120,120,120,120)));
        send(session, "PUT", "/api/profile", Map.of("revision", initial.path("revision").asLong(), "profile", profile));
        long userId = store.accountByName(initial.path("username").asText()).id();
        Models.SyncInfo synced = new Models.SyncInfo();
        synced.handle = "sample_user";
        synced.syncedAt = java.time.Instant.now().toString();
        synced.fetchStartedAt = java.time.Instant.now().getEpochSecond();
        store.saveSync(userId, synced.handle, List.of(), synced);
        JsonNode started = send(session, "POST", "/api/assessment", Map.of()).path("current");
        assertThat(started.path("problemIds").size()).isEqualTo(3);
        JsonNode active = getJson(session, "/api/state");
        JsonNode activePlan = send(session, "POST", "/api/plan/generate",
                Map.of("revision", active.path("revision").asLong())).path("workspace").path("plan");
        assertThat(activePlan.path("tasks")).isNotEmpty();
        for (JsonNode reserved : started.path("problemIds")) {
            assertThat(activePlan.path("tasks").toString()).doesNotContain(reserved.asText());
        }
        String base = "/api/assessment/" + started.path("id").asText();
        mvc.perform(post(base + "/settle").session(session)).andExpect(status().isConflict());
        var snapshot = store.snapshot(userId);
        var test = snapshot.workspace().assessment;
        long end = java.time.Instant.now().getEpochSecond() - 1;
        test.startedAt = end - 3600;
        test.deadline = end;
        store.save(userId, snapshot.revision(), snapshot.workspace());
        String first = test.problemIds.get(0), second = test.problemIds.get(1), third = test.problemIds.get(2);
        store.saveSync(userId, synced.handle, List.of(
                new Models.Submission(1, first, "OK", test.startedAt + 23),
                new Models.Submission(2, second, "PENDING", test.startedAt + 30),
                new Models.Submission(3, third, "OK", end)), synced);
        mvc.perform(post(base + "/settle").session(session)).andExpect(status().isConflict());
        store.saveSync(userId, synced.handle, List.of(new Models.Submission(2, second, "WRONG_ANSWER", test.startedAt + 30)), synced);
        JsonNode results = send(session, "POST", base + "/settle", Map.of()).path("current").path("results");
        assertThat(results.path(0).path("status").asText()).isEqualTo("accepted");
        assertThat(results.path(0).path("firstAcceptedSeconds").asLong()).isEqualTo(23);
        assertThat(results.path(1).path("status").asText()).isEqualTo("attempted");
        assertThat(results.path(2).path("status").asText()).isEqualTo("unsubmitted");
        JsonNode model = getJson(session, "/api/profile/model");
        assertThat(model.path("timedEvidence").size()).isEqualTo(3);
        assertThat(model.path("codeforcesCoverage").toString()).contains("\"accepted\":1");
        assertThat(model.path("samples").asInt()).isZero();
        assertThat(send(session, "POST", base + "/settle", Map.of()).path("current").path("results")).isEqualTo(results);
        var planSnapshot = store.snapshot(userId);
        planSnapshot.workspace().profile.dailyMinutes = List.of(120, 120, 120, 120, 120, 120, 120);
        store.save(userId, planSnapshot.revision(), planSnapshot.workspace());
        JsonNode updated = getJson(session, "/api/state");
        JsonNode plan = send(session, "POST", "/api/plan/generate", Map.of("revision", updated.path("revision").asLong()))
                .path("workspace").path("plan");
        assertThat(plan.path("basis").toString()).contains("最近限时短测中");
        assertThat(plan.path("tasks").toString()).doesNotContain(first, second, third);
        assertThat(plan.path("tasks").toString()).contains("安排一道不同题目复核");
        JsonNode reflected = send(session, "PUT", base + "/reflection", Map.of("note", " 卡在边界调试 "));
        assertThat(reflected.path("current").path("reflection").asText()).isEqualTo("卡在边界调试");
        JsonNode next = send(session, "POST", "/api/assessment", Map.of());
        assertThat(next.path("history").path(0).path("reflection").asText()).isEqualTo("卡在边界调试");
        assertThat(next.path("history").size()).isEqualTo(1);
        String nextId = next.path("current").path("id").asText();
        String nextRoute = "/api/assessment/" + nextId;
        assertThat(send(session, "POST", nextRoute + "/cancel", Map.of()).path("current").path("cancelled").asBoolean()).isTrue();
        mvc.perform(post(nextRoute + "/settle").session(session)).andExpect(status().isConflict());
        assertThat(next.path("current").path("problemIds").toString()).doesNotContain(first, second, third);
    }

    @Test
    void savesSelfReportedExperienceAndValidatesCompetitionDate() throws Exception {
        MockHttpSession session = register();
        JsonNode initial = getJson(session, "/api/state");
        var profile = (com.fasterxml.jackson.databind.node.ObjectNode) initial.path("workspace").path("profile").deepCopy();
        profile.put("achievements", " 校赛团队二等奖 ");
        profile.put("competitionDate", "2026-02-30");
        long revision = initial.path("revision").asLong();
        mvc.perform(put("/api/profile").session(session).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("revision", revision, "profile", profile))))
                .andExpect(status().isBadRequest());
        profile.put("competitionDate", "2026-12-01");
        JsonNode result = send(session, "PUT", "/api/profile", Map.of("revision", revision, "profile", profile));
        assertThat(result.path("workspace").path("profile").path("achievements").asText()).isEqualTo("校赛团队二等奖");
        assertThat(getJson(session, "/api/state").path("workspace").path("profile").path("competitionDate").asText())
                .isEqualTo("2026-12-01");
        assertThat(getJson(session, "/api/profile/model").path("samples").asInt()).isZero();
    }

    @Test
    void syncMergesWindowsAndRefreshesRejudgedVerdicts() throws Exception {
        MockHttpSession session = register();
        JsonNode initial = state(session);
        JsonNode profile = initial.path("workspace").path("profile").deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) profile).put("handle", "sample_user");
        send(session, "PUT", "/api/profile", Map.of("revision", 0, "profile", profile));
        long userId = store.accountByName(initial.path("username").asText()).id();
        store.saveSync(userId, "sample_user", List.of(new Models.Submission(1,"CF-1-A","OK",1), new Models.Submission(2,"CF-1-B","OK",2)), new Models.SyncInfo());
        store.saveSync(userId, "sample_user", List.of(new Models.Submission(1,"CF-1-A","WRONG_ANSWER",1), new Models.Submission(3,"CF-1-C","OK",3)), new Models.SyncInfo());
        assertThat(store.solved(userId, "SAMPLE_USER")).containsExactlyInAnyOrder("CF-1-B", "CF-1-C");
        assertThat(store.snapshot(userId).workspace().sync.submissionCount).isEqualTo(3);
    }

    @Test
    void stateRequiresAuthenticationAndAccountsAreIsolated() throws Exception {
        mvc.perform(get("/api/state")).andExpect(status().isUnauthorized());
        MockHttpSession first = register();
        MockHttpSession second = register();
        JsonNode firstState = state(first);
        JsonNode profile = firstState.path("workspace").path("profile").deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) profile).put("name", "first-user");
        send(first, "PUT", "/api/profile", Map.of("revision", firstState.path("revision").asLong(), "profile", profile));
        assertThat(state(first).path("workspace").path("profile").path("name").asText()).isEqualTo("first-user");
        assertThat(state(second).path("workspace").path("profile").path("name").asText()).isEqualTo("新同学");
    }

    @Test
    void staleWriteFailsAndGeneratedTasksFitBudgets() throws Exception {
        MockHttpSession session = register();
        JsonNode initial = state(session);
        long revision = initial.path("revision").asLong();
        JsonNode generated = send(session, "POST", "/api/plan/generate", Map.of("revision", revision));
        mvc.perform(post("/api/plan/generate").session(session).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("revision", revision))))
                .andExpect(status().isConflict());
        JsonNode plan = generated.path("workspace").path("plan");
        Set<String> seen = new HashSet<>();
        int[] totals = new int[7];
        Set<String> candidates = new HashSet<>();
        generated.path("problems").forEach(problem -> candidates.add(problem.path("id").asText()));
        for (JsonNode task : plan.path("tasks")) {
            assertThat(seen.add(task.path("problemId").asText())).isTrue();
            assertThat(candidates).contains(task.path("problemId").asText());
            totals[task.path("day").asInt()] += task.path("minutes").asInt();
        }
        for (int day = 0; day < 7; day++) assertThat(totals[day]).isLessThanOrEqualTo(plan.path("dailyMinutes").path(day).asInt());
    }

    @Test
    void proposalAcceptanceAndUndoPreserveFeedbackAndSolvedExclusion() throws Exception {
        MockHttpSession session = register();
        JsonNode generated = send(session, "POST", "/api/plan/generate", Map.of("revision", 0));
        // Explicitly provide time on every day so this scenario works on weekends too.
        if (generated.path("workspace").path("plan").path("tasks").isEmpty()) {
            JsonNode profile = generated.path("workspace").path("profile").deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) profile).set("dailyMinutes", json.valueToTree(List.of(90,90,90,90,90,90,90)));
            generated = send(session, "PUT", "/api/profile", Map.of("revision", generated.path("revision").asLong(), "profile", profile));
            generated = send(session, "POST", "/api/plan/generate", Map.of("revision", generated.path("revision").asLong()));
        }
        JsonNode task = generated.path("workspace").path("plan").path("tasks").path(0);
        JsonNode reviewed = send(session, "POST", "/api/feedback", Map.of("revision", generated.path("revision").asLong(), "taskId", task.path("id").asText(), "outcome", "independent", "minutes", 47, "note", "边界已理解"));
        JsonNode proposed = send(session, "POST", "/api/plan/proposal", Map.of("revision", reviewed.path("revision").asLong(), "instruction", "每天30分钟"));
        JsonNode proposal = proposed.path("workspace").path("proposal");
        assertThat(proposal.path("plan").path("dailyMinutes").path(5).asInt()).isEqualTo(30);
        JsonNode accepted = send(session, "POST", "/api/plan/accept", Map.of("revision", proposed.path("revision").asLong(), "proposalId", proposal.path("id").asText()));
        JsonNode undone = send(session, "POST", "/api/plan/undo", Map.of("revision", accepted.path("revision").asLong()));
        assertThat(undone.path("workspace").path("feedback").size()).isEqualTo(1);
        assertThat(undone.path("workspace").path("feedback").path(0).path("minutes").asInt()).isEqualTo(47);
        assertThat(undone.path("workspace").path("plan").path("version").asInt()).isGreaterThan(accepted.path("workspace").path("plan").path("version").asInt());
        assertThat(undone.path("solvedIds").toString()).contains(task.path("problemId").asText());
    }

    @Test
    void foreignTaskAndInvalidProfileAreRejected() throws Exception {
        MockHttpSession session = register();
        JsonNode generated = send(session, "POST", "/api/plan/generate", Map.of("revision", 0));
        mvc.perform(post("/api/feedback").session(session).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("revision", generated.path("revision").asLong(), "taskId", "foreign-task", "outcome", "independent", "minutes", 20, "note", ""))))
                .andExpect(status().isNotFound());
        JsonNode profile = generated.path("workspace").path("profile").deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) profile).set("dailyMinutes", json.valueToTree(List.of(-1,60,60,60,60,60,60)));
        mvc.perform(put("/api/profile").session(session).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("revision", generated.path("revision").asLong(), "profile", profile))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void feedbackBecomesMemoryAndPlanCitesIt() throws Exception {
        MockHttpSession session = register();
        JsonNode generated = seedPlan(session);
        JsonNode task = generated.path("workspace").path("plan").path("tasks").path(0);
        JsonNode reviewed = send(session, "POST", "/api/feedback", Map.of("revision", generated.path("revision").asLong(),
                "taskId", task.path("id").asText(), "outcome", "editorial", "minutes", 55,
                "note", "又是二分边界写错，最后还超时了"));

        JsonNode memory = getJson(session, "/api/memory");
        String statements = memory.path("observations").toString();
        assertThat(statements).contains("边界");
        assertThat(memory.path("events").size()).isGreaterThan(0);

        JsonNode regenerated = send(session, "POST", "/api/plan/generate", Map.of("revision", reviewed.path("revision").asLong()));
        assertThat(regenerated.path("workspace").path("plan").path("basis").size()).isGreaterThan(0);
    }

    @Test
    void memoryCanBeCorrectedAndDisabled() throws Exception {
        MockHttpSession session = register();
        JsonNode generated = seedPlan(session);
        JsonNode task = generated.path("workspace").path("plan").path("tasks").path(0);
        send(session, "POST", "/api/feedback", Map.of("revision", generated.path("revision").asLong(),
                "taskId", task.path("id").asText(), "outcome", "stuck", "minutes", 70, "note", "边界完全没想清楚"));

        JsonNode memory = getJson(session, "/api/memory");
        String id = memory.path("observations").path(0).path("id").asText();
        assertThat(id).isNotBlank();

        JsonNode corrected = json.readTree(mvc.perform(patch("/api/memory/" + id).session(session)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("statement", "边界判断需要回看模板", "active", false))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        JsonNode item = null;
        for (JsonNode candidate : corrected.path("observations")) {
            if (id.equals(candidate.path("id").asText())) { item = candidate; break; }
        }
        assertThat(item).as("停用后的观察必须仍然留在列表里，供面板整表刷新").isNotNull();
        assertThat(item.path("active").asBoolean()).isFalse();
        assertThat(item.path("statement").asText()).isEqualTo("边界判断需要回看模板");

        JsonNode restored = json.readTree(mvc.perform(patch("/api/memory/" + id).session(session)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("active", true))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(restored.path("observations").size()).isGreaterThan(1);
        assertThat(restored.path("observations").toString()).contains("边界判断需要回看模板");
    }

    @Test
    void profileModelReportsLevelSamplesAndEvidence() throws Exception {
        MockHttpSession session = register();
        JsonNode generated = seedPlan(session);
        JsonNode task = generated.path("workspace").path("plan").path("tasks").path(0);
        send(session, "POST", "/api/feedback", Map.of("revision", generated.path("revision").asLong(),
                "taskId", task.path("id").asText(), "outcome", "independent", "minutes", 28, "note", "独立完成，注意了边界"));

        JsonNode model = getJson(session, "/api/profile/model");
        assertThat(model.path("samples").asInt()).isEqualTo(1);
        assertThat(model.path("summary").asText()).isNotBlank();
        JsonNode scored = null;
        for (JsonNode topic : model.path("topics")) {
            if (topic.path("samples").asInt() > 0) { scored = topic; break; }
        }
        assertThat(scored).isNotNull();
        assertThat(scored.path("evidence").size()).isGreaterThan(0);
        assertThat(scored.path("independentRate").asDouble()).isEqualTo(1.0);
    }

    @Test
    void codeReviewFindsOverflowAndPersistsHistory() throws Exception {
        MockHttpSession session = register();
        String code = String.join("\n",
                "#include <bits/stdc++.h>",
                "using namespace std;",
                "int a[1005];",
                "int main() {",
                "  int l = 1, r = 2000000000;",
                "  while (l < r) {",
                "    int mid = (l + r) / 2;",
                "    if (a[mid] < 3) l = mid + 1; else r = mid;",
                "  }",
                "  return 0;",
                "}");
        JsonNode review = send(session, "POST", "/api/code/review",
                Map.of("problemId", "CF-706-B", "files", List.of(Map.of("name", "main.cpp", "content", code))));
        assertThat(review.path("engine").asText()).isEqualTo("rules");
        assertThat(review.path("findings").toString()).contains("溢出");
        assertThat(review.path("files").path(0).path("lines").asInt()).isEqualTo(11);

        JsonNode history = getJson(session, "/api/code/review");
        assertThat(history.size()).isEqualTo(1);
        assertThat(history.path(0).path("findings").size()).isGreaterThan(0);

        JsonNode memory = getJson(session, "/api/memory");
        assertThat(memory.path("observations").toString()).contains("代码审查");
    }

    @Test
    void codeReviewRejectsOversizedSubmission() throws Exception {
        MockHttpSession session = register();
        String big = "int main(){}" + "a".repeat(21000);
        mvc.perform(post("/api/code/review").session(session).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("files", List.of(Map.of("name", "big.cpp", "content", big))))))
                .andExpect(status().isBadRequest());
    }

    /** 保证测试当天（含周末）也能排出至少一个任务。 */
    private JsonNode seedPlan(MockHttpSession session) throws Exception {
        JsonNode generated = send(session, "POST", "/api/plan/generate", Map.of("revision", 0));
        if (generated.path("workspace").path("plan").path("tasks").isEmpty()) {
            JsonNode profile = generated.path("workspace").path("profile").deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) profile).set("dailyMinutes", json.valueToTree(List.of(90, 90, 90, 90, 90, 90, 90)));
            generated = send(session, "PUT", "/api/profile", Map.of("revision", generated.path("revision").asLong(), "profile", profile));
            generated = send(session, "POST", "/api/plan/generate", Map.of("revision", generated.path("revision").asLong()));
        }
        assertThat(generated.path("workspace").path("plan").path("tasks").size()).isGreaterThan(0);
        return generated;
    }

    private JsonNode getJson(MockHttpSession session, String path) throws Exception {
        return json.readTree(mvc.perform(get(path).session(session)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private MockHttpSession register() throws Exception {
        MockHttpSession session = new MockHttpSession();
        send(session, "POST", "/api/auth/register", Map.of("username", "u_" + UUID.randomUUID().toString().replace("-", "").substring(0,12), "password", "testing-pass123"));
        return session;
    }

    private JsonNode state(MockHttpSession session) throws Exception {
        return json.readTree(mvc.perform(get("/api/state").session(session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private JsonNode send(MockHttpSession session, String method, String path, Object body) throws Exception {
        var builder = method.equals("PUT") ? put(path) : post(path);
        return json.readTree(mvc.perform(builder.session(session).contentType(MediaType.APPLICATION_JSON).characterEncoding("UTF-8").content(json.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }
}
