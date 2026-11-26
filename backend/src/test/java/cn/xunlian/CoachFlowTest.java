package cn.xunlian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    @DynamicPropertySource
    static void sqlite(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DB_URL);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired CoachStore store;

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
