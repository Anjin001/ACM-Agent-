package cn.xunlian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:coachtests;MODE=MySQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class CoachFlowTest {
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
