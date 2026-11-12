package cn.xunlian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:sync_tests;MODE=MySQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class CodeforcesSyncTest {
    static final HttpServer server;
    static final ObjectMapper fixtureJson = new ObjectMapper();
    static final java.util.concurrent.ExecutorService serverThreads = Executors.newCachedThreadPool();
    static {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(serverThreads);
            server.createContext("/api/", exchange -> {
                String query = Objects.toString(exchange.getRequestURI().getQuery(), "");
                if (exchange.getRequestURI().getPath().endsWith("user.info")) {
                    byte[] body = fixtureJson.writeValueAsBytes(Map.of("status","OK","result",List.of(Map.of("handle","large_history","rating",2200))));
                    exchange.sendResponseHeaders(200,body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                    return;
                }
                Map<String,String> params = new HashMap<>();
                for (String part : query.split("&")) {
                    String[] pair = part.split("=",2);
                    if (pair.length==2) params.put(pair[0],pair[1]);
                }
                int from = Integer.parseInt(params.get("from"));
                if (query.contains("failing") && from > 1000) {
                    exchange.sendResponseHeaders(503,-1);
                    exchange.close();
                    return;
                }
                if (query.contains("slow")) {
                    try { Thread.sleep(300); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                }
                List<Map<String,Object>> rows = new ArrayList<>();
                for(int index=from-1;index<Math.min(from+999,11001);index++) {
                    rows.add(Map.of("id",20000-index,"verdict","OK","creationTimeSeconds",1700000000+index,
                            "problem",Map.of("contestId",4,"index","A","name","Watermelon","tags",List.of("math","brute force"),"rating",800)));
                }
                byte[] body = fixtureJson.writeValueAsBytes(Map.of("status","OK","result",rows));
                exchange.getResponseHeaders().add("Content-Type","application/json");
                exchange.sendResponseHeaders(200,body.length);
                try { exchange.getResponseBody().write(body); } finally { exchange.close(); }
            });
            server.start();
        } catch(Exception ex) { throw new ExceptionInInitializerError(ex); }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("cf.api-base", () -> "http://127.0.0.1:" + server.getAddress().getPort() + "/api/");
        registry.add("cf.interval-ms", () -> 0);
    }

    @AfterAll static void stopServer() { server.stop(0); serverThreads.shutdownNow(); }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired CoachStore store;

    @Test
    void completeHistoryBeyondTenThousandAndIncrementalDedupWorkThroughApi() throws Exception {
        MockHttpSession session = account("large_history");
        send(session,"POST","/api/sync",Map.of("mode","full"));
        JsonNode job = awaitJob(session);
        assertThat(job.path("status").asText()).isEqualTo("success");
        assertThat(job.path("readCount").asInt()).isEqualTo(11001);
        JsonNode state = getJson(session,"/api/state");
        assertThat(state.path("workspace").path("sync").path("submissionCount").asInt()).isEqualTo(11001);
        assertThat(state.path("workspace").path("sync").path("fullHistory").asBoolean()).isTrue();
        JsonNode solved = getJson(session,"/api/cf/solved?q=Watermelon");
        assertThat(solved.path("total").asInt()).isEqualTo(1);
        assertThat(solved.path("items").path(0).path("acceptedSubmissions").asInt()).isEqualTo(11001);
        assertThat(solved.path("items").path(0).path("problem").path("url").asText()).isEqualTo("https://codeforces.com/problemset/problem/4/A");
        send(session,"POST","/api/sync",Map.of("mode","incremental"));
        JsonNode incremental = awaitJob(session);
        assertThat(incremental.path("mode").asText()).isEqualTo("incremental");
        assertThat(incremental.path("readCount").asInt()).isEqualTo(1000);
        assertThat(getJson(session,"/api/state").path("workspace").path("sync").path("submissionCount").asInt()).isEqualTo(11001);
        assertThat(getJson(session,"/api/problems?q=Watermelon&completion=unsolved").path("total").asInt()).isZero();
        JsonNode profile = state.path("workspace").path("profile").deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode)profile).put("handle","other_user");
        long revision = getJson(session,"/api/state").path("revision").asLong();
        send(session,"PUT","/api/profile",Map.of("revision",revision,"profile",profile));
        assertThat(getJson(session,"/api/cf/solved").path("total").asInt()).isZero();
    }

    @Test
    void failedFullSyncKeepsPreviousDataAndCancellationFinishes() throws Exception {
        MockHttpSession session = account("failing");
        JsonNode state = getJson(session,"/api/state");
        long userId = store.accountByName(state.path("username").asText()).id();
        Models.SyncInfo info = new Models.SyncInfo();
        info.handle="failing";
        store.saveSync(userId,"failing",List.of(new Models.Submission(1,"CF-4-A","OK",1700000000)),info);
        send(session,"POST","/api/sync",Map.of("mode","full"));
        assertThat(awaitJob(session).path("status").asText()).isEqualTo("error");
        assertThat(getJson(session,"/api/state").path("workspace").path("sync").path("submissionCount").asInt()).isEqualTo(1);
        MockHttpSession slow = account("slow");
        send(slow,"POST","/api/sync",Map.of("mode","full"));
        send(slow,"POST","/api/sync/cancel",Map.of());
        assertThat(awaitJob(slow).path("status").asText()).isEqualTo("cancelled");
        assertThat(getJson(slow,"/api/cf/solved").path("total").asInt()).isZero();
    }

    @Test
    void legacyAcceptedRecordsOutsideCatalogStillHaveOriginalLinks() throws Exception {
        MockHttpSession session = account("legacy_user");
        JsonNode state = getJson(session,"/api/state");
        long userId = store.accountByName(state.path("username").asText()).id();
        Models.SyncInfo info = new Models.SyncInfo();
        info.handle = "legacy_user";
        store.saveSync(userId,"legacy_user",List.of(
                new Models.Submission(910001,"CF-99999-Z9","OK",1700000000),
                new Models.Submission(910002,"CF-999999-A","OK",1700000010)),info);
        JsonNode solved = getJson(session,"/api/cf/solved");
        assertThat(solved.path("total").asInt()).isEqualTo(2);
        assertThat(solved.path("items").path(0).path("problem").path("url").asText()).isEqualTo("https://codeforces.com/gym/999999/problem/A");
        assertThat(solved.path("items").path(1).path("problem").path("url").asText()).isEqualTo("https://codeforces.com/problemset/problem/99999/Z9");
        assertThat(getJson(session,"/api/cf/solved?q=99999Z9").path("total").asInt()).isEqualTo(1);
    }

    @Test
    void catalogSupportsFineTopicsAdvancedLevelsAndClampedPagination() throws Exception {
        MockHttpSession session = account("topics_user");
        JsonNode state = getJson(session,"/api/state");
        assertThat(state.path("topics").size()).isGreaterThanOrEqualTo(80);
        assertThat(state.path("catalogTotal").asInt()).isGreaterThan(11000);
        JsonNode page = getJson(session,"/api/problems?topic="+java.net.URLEncoder.encode("Dijkstra",StandardCharsets.UTF_8));
        assertThat(page.path("items").toString()).contains("P4779");
        JsonNode advanced = getJson(session,"/api/problems?minRating=2600&maxRating=3000&page=99999&pageSize=10");
        assertThat(advanced.path("total").asInt()).isPositive();
        assertThat(advanced.path("page").asInt()).isEqualTo(advanced.path("pages").asInt());
        JsonNode profile = state.path("workspace").path("profile").deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode)profile).put("level",2800);
        ((com.fasterxml.jackson.databind.node.ObjectNode)profile).set("topics",json.valueToTree(List.of("网络流")));
        send(session,"PUT","/api/profile",Map.of("revision",state.path("revision").asLong(),"profile",profile));
        JsonNode current = getJson(session,"/api/state");
        send(session,"POST","/api/plan/generate",Map.of("revision",current.path("revision").asLong()));
    }

    private MockHttpSession account(String handle) throws Exception {
        MockHttpSession session=new MockHttpSession();
        JsonNode state=send(session,"POST","/api/auth/register",Map.of("username","v_"+UUID.randomUUID().toString().substring(0,12).replace("-",""),"password","testing-pass123"));
        JsonNode profile=state.path("workspace").path("profile").deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode)profile).put("handle",handle);
        send(session,"PUT","/api/profile",Map.of("revision",state.path("revision").asLong(),"profile",profile));
        return session;
    }
    private JsonNode awaitJob(MockHttpSession session) throws Exception {
        long deadline=System.currentTimeMillis()+15000;
        JsonNode job;
        do {
            job=getJson(session,"/api/sync");
            if(!job.path("status").asText().equals("running"))return job;
            Thread.sleep(30);
        }while(System.currentTimeMillis()<deadline);
        throw new AssertionError("Sync did not finish");
    }
    private JsonNode getJson(MockHttpSession session,String path) throws Exception {
        return json.readTree(mvc.perform(get(path).session(session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
    private JsonNode send(MockHttpSession session,String method,String path,Object body) throws Exception {
        var request=method.equals("PUT")?put(path):post(path);
        return json.readTree(mvc.perform(request.session(session).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
