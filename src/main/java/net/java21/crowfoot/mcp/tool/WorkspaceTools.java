package net.java21.crowfoot.mcp.tool;

import io.modelcontextprotocol.common.McpTransportContext;
import java.util.LinkedHashMap;
import java.util.Map;
import net.java21.crowfoot.mcp.auth.Caller;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 읽기 도구 — 워크스페이스, 문서 목록, 설계 기준 (10-mcp/00-mcp-server.md Section 4.1) */
@Component
public class WorkspaceTools {

    private final ToolSupport support;

    public WorkspaceTools(ToolSupport support) {
        this.support = support;
    }

    @McpTool(name = "get_workspace", title = "연결된 워크스페이스",
            description = "이 연결이 묶인 Crowfoot 워크스페이스의 이름과 내 역할을 돌려준다. 작업을 시작할 때 먼저 불러 어느 워크스페이스인지 사용자에게 알린다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public String getWorkspace(McpTransportContext context) {
        Caller caller = support.caller(context);
        JsonNode workspace = support.core().get(caller, support.workspacePath(caller, "")).path("response");
        ObjectNode out = support.object();
        out.put("workspaceId", caller.workspaceId());
        out.set("name", workspace.path("name"));
        out.set("description", workspace.path("description"));
        out.set("myRole", workspace.path("myRole"));
        out.put("canWrite", "OWNER".equals(workspace.path("myRole").asString("")) || "EDITOR".equals(workspace.path("myRole").asString("")));
        return support.text(out);
    }

    @McpTool(name = "list_documents", title = "ERD 문서 목록",
            description = "워크스페이스의 ERD 문서 목록. 문서 ID, 이름, 대상 DBMS, 버전, 주소를 돌려준다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public String listDocuments(McpTransportContext context,
            @McpToolParam(required = false, description = "문서 이름이나 설명에서 찾을 글자") String keyword) {
        Caller caller = support.caller(context);
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("keyword", keyword);
        query.put("page", 1);
        query.put("size", 100);
        JsonNode body = support.core().get(caller, support.workspacePath(caller, "/models"), query);
        ArrayNode documents = support.object().arrayNode();
        for (JsonNode model : body.path("responses")) {
            ObjectNode item = documents.addObject();
            String id = model.path("modelId").asString();
            item.put("documentId", id);
            item.set("name", model.path("name"));
            item.set("description", model.path("description"));
            item.set("databaseType", model.path("databaseType"));
            item.set("version", model.path("version"));
            item.set("updatedAt", model.path("updatedAt"));
            item.put("connected", !model.path("sourceConnectionId").isNull() && !model.path("sourceConnectionId").isMissingNode());
            item.put("url", support.documentUrl(caller, id));
        }
        ObjectNode out = support.object();
        out.set("totalCount", body.path("totalCount"));
        out.set("documents", documents);
        return support.text(out);
    }

    @McpTool(name = "get_design_context", title = "설계 기준",
            description = "ERD를 설계하기 전에 읽는 기준. 지원 DBMS, 타입 코드와 입력 규칙, 워크스페이스 사전(단어와 용어), 도메인 타입을 돌려준다. 사전에 있는 이름과 도메인 타입을 먼저 쓴다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public String getDesignContext(McpTransportContext context) {
        Caller caller = support.caller(context);
        ObjectNode out = support.object();

        ArrayNode databaseTypes = out.putArray("databaseTypes");
        for (JsonNode type : support.core().get(caller, "/core/database-types").path("responses")) {
            databaseTypes.addObject().put("code", type.path("code").asString()).put("displayName", type.path("displayName").asString());
        }

        out.set("rules", support.tree(DesignRules.RULES));

        // 사전 — 도메인 타입을 가리키거나 타입이 있는 항목이 용어(컬럼 이름 전체), 없는 항목이 단어(이름 조각)다
        Map<String, String> domainNames = new LinkedHashMap<>();
        ArrayNode domainTypes = out.putArray("domainTypes");
        for (JsonNode type : support.core().get(caller, support.workspacePath(caller, "/domain-types")).path("responses")) {
            domainNames.put(type.path("domainTypeId").asString(), type.path("name").asString());
            ObjectNode item = domainTypes.addObject();
            for (String field : new String[] {"name", "dataType", "length", "precision", "scale", "nullable", "defaultValue", "description"}) {
                item.set(field, type.path(field));
            }
        }
        ArrayNode words = out.putArray("dictionaryWords");
        ArrayNode terms = out.putArray("dictionaryTerms");
        for (JsonNode term : support.core().get(caller, support.workspacePath(caller, "/terms")).path("responses")) {
            String domainTypeId = term.path("domainTypeId").asString("");
            boolean isTerm = !domainTypeId.isEmpty() || (term.path("types").isObject() && !term.path("types").isEmpty());
            ObjectNode item = (isTerm ? terms : words).addObject();
            item.set("name", term.path("term"));
            item.set("logicalName", term.path("label"));
            if (!domainTypeId.isEmpty()) {
                item.put("domainType", domainNames.getOrDefault(domainTypeId, ""));
            }
        }
        out.put("dictionaryNote", "dictionaryTerms는 컬럼 이름 전체의 표준이다 — 같은 뜻의 컬럼에는 그 이름과 domainType을 쓴다. "
                + "dictionaryWords는 이름 조각의 표준이다 — 컬럼 이름을 지을 때 그 조각을 쓴다.");
        return support.text(out);
    }
}
