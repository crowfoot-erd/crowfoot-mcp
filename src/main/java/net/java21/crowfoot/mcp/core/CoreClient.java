package net.java21.crowfoot.mcp.core;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import net.java21.crowfoot.mcp.auth.Caller;
import net.java21.crowfoot.mcp.config.McpProperties;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * core 호출 — 받은 헤더를 그대로 붙여 core의 구현 경로(/core/…)를 부른다 (10-mcp/00-mcp-server.md Section 2).
 * Gateway를 거치지 않는 내부망 호출이다. core는 토큰으로 온 요청의 범위를 다시 판정한다(08-core/18-access-token.md Section 4).
 */
@Component
public class CoreClient {

    private final RestClient rest;
    private final JsonMapper json;
    private final String baseUrl;
    private final String databaseManagerBaseUrl;

    public CoreClient(McpProperties properties, JsonMapper json) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);
        // 배포·마이그레이션 실행은 문장 수만큼 걸린다 — MCP 요청 타임아웃(120초)보다 조금 짧게 둔다
        factory.setReadTimeout(110_000);
        this.rest = RestClient.builder().requestFactory(factory).build();
        this.json = json;
        this.baseUrl = properties.coreBaseUrl();
        this.databaseManagerBaseUrl = properties.databaseManagerBaseUrl();
    }

    /** GET — 응답 본문 전체(header·response·responses…)를 돌려준다 */
    public JsonNode get(Caller caller, String path, Map<String, ?> query) {
        return exchange(caller, HttpMethod.GET, path, query, null);
    }

    public JsonNode get(Caller caller, String path) {
        return exchange(caller, HttpMethod.GET, path, Map.of(), null);
    }

    /** POST — body는 JSON으로 보낸다(null이면 본문 없음) */
    public JsonNode post(Caller caller, String path, Object body) {
        return exchange(caller, HttpMethod.POST, path, Map.of(), body);
    }

    /**
     * DB 매니저 POST — 같은 헤더를 붙여 DB 매니저의 구현 경로(/database-manager/…)를 부른다.
     * 토큰으로 온 요청이 부를 수 있는 것은 샘플 데이터 넣기뿐이다(09-database-manager/00-data-browser.md Section 3.8).
     */
    public JsonNode postToDatabaseManager(Caller caller, String path, Object body) {
        return exchange(databaseManagerBaseUrl, caller, HttpMethod.POST, path, Map.of(), body);
    }

    private JsonNode exchange(Caller caller, HttpMethod method, String path, Map<String, ?> query, Object body) {
        return exchange(baseUrl, caller, method, path, query, body);
    }

    private JsonNode exchange(String base, Caller caller, HttpMethod method, String path, Map<String, ?> query, Object body) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(base + path);
        query.forEach((name, value) -> {
            if (value != null && !String.valueOf(value).isBlank()) {
                builder.queryParam(name, value);
            }
        });
        URI uri = builder.encode(StandardCharsets.UTF_8).build().toUri();
        try {
            RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                    .header(Caller.HEADER_USER_ID, caller.userId())
                    .header(Caller.HEADER_WORKSPACE_ID, caller.workspaceId())
                    .accept(MediaType.APPLICATION_JSON);
            if (caller.tokenId() != null) {
                spec.header(Caller.HEADER_TOKEN_ID, caller.tokenId());
            }
            if (body != null) {
                spec.contentType(MediaType.APPLICATION_JSON).body(json.writeValueAsString(body));
            }
            return spec.exchange((request, response) -> {
                int status = response.getStatusCode().value();
                byte[] bytes = response.getBody().readAllBytes();
                JsonNode node = bytes.length == 0 ? json.createObjectNode() : parse(bytes, status);
                if (status >= 400) {
                    throw toException(status, node);
                }
                return node;
            });
        } catch (ResourceAccessException e) {
            throw new CoreException(503, "CORE_UNREACHABLE", "Crowfoot 서버에 닿지 못했습니다. 잠시 뒤 다시 시도하세요.");
        }
    }

    private JsonNode parse(byte[] bytes, int status) {
        try {
            return json.readTree(bytes);
        } catch (RuntimeException e) {
            throw new CoreException(status, "UNREADABLE_RESPONSE", "Crowfoot 서버의 응답을 읽지 못했습니다(HTTP " + status + ").");
        }
    }

    /** core의 실패 응답을 문장으로 — resultCode, 메시지, 항목별 사유(errors) */
    private static CoreException toException(int status, JsonNode node) {
        JsonNode header = node.path("header");
        String code = header.path("resultCode").asString("HTTP_" + status);
        StringBuilder message = new StringBuilder(code).append(": ").append(header.path("resultMessage").asString("요청이 거절됐습니다"));
        JsonNode errors = node.path("errors");
        if (errors.isArray() && !errors.isEmpty()) {
            for (JsonNode error : errors) {
                message.append("\n- ").append(error.path("field").asString("")).append(": ").append(error.path("message").asString(""));
            }
        }
        if (status == 403) {
            message.append("\n(이 토큰의 역할로는 할 수 없는 일이거나, MCP로는 막혀 있는 일입니다.)");
        }
        return new CoreException(status, code, message.toString());
    }
}
