package net.java21.crowfoot.mcp.tool;

import io.modelcontextprotocol.common.McpTransportContext;
import net.java21.crowfoot.mcp.auth.Caller;
import net.java21.crowfoot.mcp.config.McpProperties;
import net.java21.crowfoot.mcp.core.CoreClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 도구 공통 — 호출자 확인, core 경로, 문서 주소, 응답 직렬화 (10-mcp/00-mcp-server.md Section 3).
 * 도구는 워크스페이스를 입력으로 받지 않는다. 경로의 워크스페이스는 항상 헤더의 값이다.
 */
@Component
public class ToolSupport {

    private final CoreClient core;
    private final JsonMapper json;
    private final String webBaseUrl;

    public ToolSupport(CoreClient core, JsonMapper json, McpProperties properties) {
        this.core = core;
        this.json = json;
        this.webBaseUrl = properties.webBaseUrl();
    }

    public Caller caller(McpTransportContext context) {
        return Caller.from(context);
    }

    public CoreClient core() {
        return core;
    }

    public ObjectNode object() {
        return json.createObjectNode();
    }

    public tools.jackson.databind.node.ArrayNode array() {
        return json.createArrayNode();
    }

    public JsonNode tree(Object value) {
        return json.valueToTree(value);
    }

    /** /core/workspaces/{헤더의 워크스페이스} + 나머지 경로 */
    public String workspacePath(Caller caller, String rest) {
        return "/core/workspaces/" + caller.workspaceId() + rest;
    }

    /** /core/workspaces/{ws}/models/{documentId} + 나머지 경로 — documentId는 숫자만 받는다(경로 조작 방지) */
    public String modelPath(Caller caller, String documentId, String rest) {
        if (documentId == null || !documentId.matches("\\d{1,19}")) {
            throw new IllegalArgumentException("documentId는 list_documents가 돌려준 문서 ID(숫자)여야 합니다");
        }
        return workspacePath(caller, "/models/" + documentId + rest);
    }

    /** ID로 쓰는 입력 — 숫자만 받는다 */
    public String id(String name, String value) {
        if (value == null || !value.matches("\\d{1,19}")) {
            throw new IllegalArgumentException(name + "는 숫자 ID여야 합니다");
        }
        return value;
    }

    /** 사용자가 브라우저에서 여는 문서 주소 */
    public String documentUrl(Caller caller, String documentId) {
        return webBaseUrl + "/workspaces/" + caller.workspaceId() + "/models/" + documentId;
    }

    /** 웹 경로(/community/posts/1)를 사용자가 여는 주소로 */
    public String webUrl(String path) {
        return webBaseUrl + (path.startsWith("/") ? path : "/" + path);
    }

    /** 문서를 고친 뒤의 버전 메모 — 출처를 적는다 */
    public String note(String what) {
        return "Claude(MCP): " + what;
    }

    public String text(JsonNode node) {
        return json.writerWithDefaultPrettyPrinter().writeValueAsString(node);
    }
}
