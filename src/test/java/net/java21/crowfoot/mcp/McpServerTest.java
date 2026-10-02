package net.java21.crowfoot.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * MCP 서버를 실제로 띄우고 /mcp에 JSON-RPC를 보낸다. core는 같은 프로세스의 작은 HTTP 서버가 흉내 낸다.
 * 확인하는 것 — 헤더 없는 요청 거부, 헤더를 core에 그대로 전달, 워크스페이스는 헤더에서만,
 * core의 거절을 문장으로, 실행 전 확인(버전·계획 지문·삭제 문장)  (10-mcp/00-mcp-server.md Section 2·6·10)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpServerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static HttpServer core;
    /** core가 받은 요청 — "METHOD path" 순서대로 */
    private static final List<Received> received = new CopyOnWriteArrayList<>();
    /** "METHOD path" → 응답 */
    private static final Map<String, Stub> stubs = new ConcurrentHashMap<>();

    record Received(String method, String path, Map<String, String> headers, String body) {
    }

    record Stub(int status, String body) {
    }

    @LocalServerPort
    int port;

    @BeforeAll
    static void startCore() throws IOException {
        core = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        core.createContext("/", McpServerTest::handle);
        core.start();
    }

    @AfterAll
    static void stopCore() {
        core.stop(0);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("crowfoot.mcp.core-base-url", () -> "http://127.0.0.1:" + core.getAddress().getPort());
        registry.add("crowfoot.mcp.web-base-url", () -> "https://crowfoot.example");
    }

    @BeforeEach
    void reset() {
        received.clear();
        stubs.clear();
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String key = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
        Map<String, String> headers = new ConcurrentHashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(), values.get(0)));
        received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().toString(), headers,
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
        Stub stub = stubs.getOrDefault(key, new Stub(404, "{\"header\":{\"isSuccessful\":false,\"resultCode\":\"NO_STUB\",\"resultMessage\":\"" + key + "\"}}"));
        byte[] bytes = stub.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(stub.status(), bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void stub(String key, int status, String body) {
        stubs.put(key, new Stub(status, body));
    }

    private static String ok(String response) {
        return "{\"header\":{\"isSuccessful\":true,\"resultCode\":\"SUCCESS\",\"resultMessage\":\"SUCCESS\"},\"response\":" + response + "}";
    }

    private static String list(String responses) {
        return "{\"header\":{\"isSuccessful\":true,\"resultCode\":\"SUCCESS\",\"resultMessage\":\"SUCCESS\"},\"responses\":" + responses + ",\"totalCount\":1}";
    }

    private HttpResponse<String> rpc(String body, boolean withHeaders) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (withHeaders) {
            request.header("X-USER-ID", "2").header("X-TOKEN-WORKSPACE-ID", "77").header("X-ACCESS-TOKEN-ID", "12");
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 도구를 부르고 결과(result)를 돌려준다 */
    private JsonNode call(String tool, String arguments) throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool + "\",\"arguments\":" + arguments + "}}";
        return JSON.readTree(rpc(body, true).body()).path("result");
    }

    private static JsonNode text(JsonNode result) {
        return JSON.readTree(result.path("content").get(0).path("text").asString());
    }

    private static List<String> calls() {
        List<String> calls = new ArrayList<>();
        received.forEach(r -> calls.add(r.method() + " " + r.path()));
        return calls;
    }

    @Test
    void Gateway가_넣는_헤더가_없으면_401이다_도구_목록도_마찬가지다() throws Exception {
        HttpResponse<String> response = rpc("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}", false);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(received).isEmpty();
    }

    @Test
    void 도구는_18개이고_지우거나_실행하는_도구만_파괴적으로_표시한다() throws Exception {
        JsonNode tools = JSON.readTree(rpc("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}", true).body()).path("result").path("tools");

        List<String> names = new ArrayList<>();
        List<String> destructive = new ArrayList<>();
        for (JsonNode tool : tools) {
            names.add(tool.path("name").asString());
            if (tool.path("annotations").path("destructiveHint").asBoolean()) {
                destructive.add(tool.path("name").asString());
            }
            // 워크스페이스를 입력으로 받는 도구는 없다 — 헤더의 워크스페이스만 쓴다
            assertThat(tool.path("inputSchema").path("properties").has("workspaceId")).isFalse();
        }
        assertThat(names).containsExactlyInAnyOrder("get_workspace", "list_documents", "get_document", "get_design_context",
                "validate_document", "export_ddl", "create_document", "import_ddl", "save_requirements", "apply_schema", "remove_objects",
                "list_databases", "issue_database", "list_connections", "plan_deployment", "deploy_document", "plan_migration", "apply_migration");
        assertThat(destructive).containsExactlyInAnyOrder("remove_objects", "deploy_document", "apply_migration");
    }

    @Test
    void 받은_헤더_세_개를_core에_그대로_붙이고_경로의_워크스페이스는_헤더_값이다() throws Exception {
        stub("GET /core/workspaces/77", 200, ok("{\"workspaceId\":\"77\",\"name\":\"쇼핑몰\",\"description\":null,\"myRole\":\"EDITOR\"}"));

        JsonNode result = call("get_workspace", "{}");

        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(text(result).path("name").asString()).isEqualTo("쇼핑몰");
        assertThat(text(result).path("canWrite").asBoolean()).isTrue();
        Received request = received.get(0);
        assertThat(request.path()).isEqualTo("/core/workspaces/77");
        assertThat(request.headers()).containsEntry("x-user-id", "2").containsEntry("x-token-workspace-id", "77").containsEntry("x-access-token-id", "12");
        assertThat(request.headers()).doesNotContainKey("authorization");
    }

    @Test
    void core가_거절하면_코드와_항목별_사유를_문장으로_돌려준다() throws Exception {
        stub("POST /core/workspaces/77/models/501/schema/apply", 400,
                "{\"header\":{\"isSuccessful\":false,\"resultCode\":\"INVALID_REQUEST\",\"resultMessage\":\"입력이 올바르지 않습니다\"},"
                        + "\"errors\":[{\"field\":\"tables[0].columns[1].dataType\",\"message\":\"VARCHAR2는 타입 코드가 아닙니다\"}]}");

        JsonNode result = call("apply_schema", "{\"documentId\":\"501\",\"tables\":[{\"physicalName\":\"orders\"}]}");

        assertThat(result.path("isError").asBoolean()).isTrue();
        String message = result.path("content").get(0).path("text").asString();
        assertThat(message).contains("INVALID_REQUEST").contains("tables[0].columns[1].dataType").contains("VARCHAR2는 타입 코드가 아닙니다");
    }

    @Test
    void 편집_도구는_주지_않은_필드를_보내지_않고_버전_메모에_출처를_적는다() throws Exception {
        stub("POST /core/workspaces/77/models/501/requirements/apply", 200,
                ok("{\"version\":8,\"changed\":true,\"summary\":[],\"warnings\":[],\"requirements\":{\"counts\":{},\"pending\":[\"REQ-003\"]}}"));

        JsonNode result = call("save_requirements",
                "{\"documentId\":\"501\",\"items\":[{\"code\":\"REQ-003\",\"title\":\"주문 생성\",\"status\":\"confirmed\"}]}");

        JsonNode body = JSON.readTree(received.get(0).body());
        assertThat(body.path("items").get(0).propertyNames()).containsExactlyInAnyOrder("code", "title", "status");
        assertThat(body.path("note").asString()).startsWith("Claude(MCP)");
        assertThat(body.has("baseVersion")).isFalse();
        assertThat(text(result).path("url").asString()).isEqualTo("https://crowfoot.example/workspaces/77/models/501");
        assertThat(text(result).path("next").asString()).contains("반영 대기");
    }

    @Test
    void 문서_ID가_숫자가_아니면_core를_부르지_않는다() throws Exception {
        JsonNode result = call("get_document", "{\"documentId\":\"501/../../9\"}");

        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(received).isEmpty();
    }

    @Test
    void 배포는_계획에서_본_버전과_문서의_지금_버전이_다르면_실행하지_않는다() throws Exception {
        stub("GET /core/workspaces/77/models/501/outline", 200, ok("{\"name\":\"쇼핑몰 ERD\",\"version\":9,\"databaseType\":\"postgresql\",\"sourceConnectionId\":null}"));

        JsonNode result = call("deploy_document", "{\"documentId\":\"501\",\"connectionId\":\"901\",\"documentVersion\":8}");

        assertThat(text(result).path("executed").asBoolean()).isFalse();
        assertThat(calls()).containsExactly("GET /core/workspaces/77/models/501/outline");
    }

    @Test
    void 배포하면_문서를_그_커넥션에_연결한다() throws Exception {
        stub("GET /core/workspaces/77/models/501/outline", 200, ok("{\"name\":\"쇼핑몰 ERD\",\"version\":9,\"databaseType\":\"postgresql\",\"sourceConnectionId\":null}"));
        stub("POST /core/workspaces/77/models/501/deploy", 200, ok("{\"executedCount\":3,\"failedCount\":0,\"statements\":[],\"warnings\":[]}"));
        stub("POST /core/workspaces/77/models/501/connections", 200, ok("{}"));

        JsonNode result = call("deploy_document", "{\"documentId\":\"501\",\"connectionId\":\"901\",\"documentVersion\":9}");

        assertThat(text(result).path("executed").asBoolean()).isTrue();
        assertThat(text(result).path("connected").asBoolean()).isTrue();
        assertThat(calls()).containsExactly("GET /core/workspaces/77/models/501/outline",
                "POST /core/workspaces/77/models/501/deploy", "POST /core/workspaces/77/models/501/connections");
        assertThat(JSON.readTree(received.get(1).body()).path("connectionId").asString()).isEqualTo("901");
    }

    private void stubMigration(String sql, String warnings, boolean allowed) {
        stub("GET /core/workspaces/77/models/501/outline", 200, ok("{\"name\":\"쇼핑몰 ERD\",\"version\":9,\"databaseType\":\"postgresql\",\"sourceConnectionId\":\"901\"}"));
        stub("GET /core/workspaces/77/connections", 200,
                list("[{\"connectionId\":\"901\",\"name\":\"개발 PG\",\"dbmsType\":\"postgresql\",\"databaseName\":\"shop\",\"host\":\"db.internal\",\"username\":\"app\",\"managed\":false,\"mcpApplyAllowed\":" + allowed + "}]"));
        stub("GET /core/workspaces/77/models/501/connections/901/migration", 200,
                ok("{\"sql\":" + JSON.writeValueAsString(sql) + ",\"warnings\":" + warnings + ",\"statementCount\":1}"));
        stub("POST /core/workspaces/77/models/501/connections/901/migration/execute", 200,
                ok("{\"executedCount\":1,\"failedCount\":0,\"statements\":[],\"warnings\":[]}"));
    }

    private static String fingerprint(String sql) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sql.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void 변경_계획은_지문과_버전을_돌려주고_접속_정보는_돌려주지_않는다() throws Exception {
        stubMigration("ALTER TABLE users ADD COLUMN grade VARCHAR(10);", "[]", true);

        JsonNode plan = text(call("plan_migration", "{\"documentId\":\"501\"}"));

        assertThat(plan.path("planFingerprint").asString()).isEqualTo(fingerprint("ALTER TABLE users ADD COLUMN grade VARCHAR(10);"));
        assertThat(plan.path("documentVersion").asLong()).isEqualTo(9);
        assertThat(plan.path("executable").asBoolean()).isTrue();
        assertThat(plan.path("target").has("host")).isFalse();
        assertThat(plan.path("target").has("username")).isFalse();
        assertThat(calls()).noneMatch(call -> call.startsWith("POST"));
    }

    @Test
    void 변경_반영은_사용자가_본_계획과_지문이_다르면_실행하지_않고_새_계획을_돌려준다() throws Exception {
        stubMigration("ALTER TABLE users ADD COLUMN grade VARCHAR(20);", "[]", true);

        JsonNode result = text(call("apply_migration",
                "{\"documentId\":\"501\",\"planFingerprint\":\"" + fingerprint("ALTER TABLE users ADD COLUMN grade VARCHAR(10);") + "\",\"documentVersion\":9}"));

        assertThat(result.path("executed").asBoolean()).isFalse();
        assertThat(result.path("sql").asString()).contains("VARCHAR(20)");
        assertThat(calls()).noneMatch(call -> call.startsWith("POST"));
    }

    @Test
    void 삭제_문장이_있으면_승인_값이_있을_때만_실행한다() throws Exception {
        String sql = "ALTER TABLE users DROP COLUMN grade;";
        stubMigration(sql, "[{\"code\":\"DESTRUCTIVE\",\"message\":\"파괴적 연산 포함\"}]", true);
        String arguments = "{\"documentId\":\"501\",\"planFingerprint\":\"" + fingerprint(sql) + "\",\"documentVersion\":9";

        JsonNode refused = text(call("apply_migration", arguments + "}"));
        assertThat(refused.path("executed").asBoolean()).isFalse();
        assertThat(refused.path("reason").asString()).contains("DESTRUCTIVE");
        assertThat(calls()).noneMatch(call -> call.startsWith("POST"));

        JsonNode executed = text(call("apply_migration", arguments + ",\"acceptDestructive\":true}"));
        assertThat(executed.path("executed").asBoolean()).isTrue();
        assertThat(calls()).contains("POST /core/workspaces/77/models/501/connections/901/migration/execute");
    }

    @Test
    void 허용되지_않은_커넥션에는_계획만_보여_주고_실행하지_않는다() throws Exception {
        String sql = "ALTER TABLE users ADD COLUMN grade VARCHAR(10);";
        stubMigration(sql, "[]", false);

        JsonNode plan = text(call("plan_migration", "{\"documentId\":\"501\"}"));
        assertThat(plan.path("executable").asBoolean()).isFalse();
        assertThat(plan.path("blocked").asString()).contains("MCP 반영");

        JsonNode result = text(call("apply_migration",
                "{\"documentId\":\"501\",\"planFingerprint\":\"" + fingerprint(sql) + "\",\"documentVersion\":9}"));
        assertThat(result.path("executed").asBoolean()).isFalse();
        assertThat(calls()).noneMatch(call -> call.startsWith("POST"));
    }
}
