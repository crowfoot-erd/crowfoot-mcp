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

/** 문서 읽기와 만들기 (10-mcp/00-mcp-server.md Section 4.1·4.2) */
@Component
public class DocumentTools {

    private final ToolSupport support;

    public DocumentTools(ToolSupport support) {
        this.support = support;
    }

    @McpTool(name = "get_document", title = "문서 읽기",
            description = "ERD 문서의 지금 상태. 요구사항(상태 판정 포함), 테이블, 컬럼, 키, 관계, 그룹을 이름 기준으로 돌려준다. 기존 문서를 고치기 전에 반드시 먼저 읽는다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public String getDocument(McpTransportContext context,
            @McpToolParam(description = "문서 ID(list_documents의 documentId)") String documentId) {
        Caller caller = support.caller(context);
        JsonNode outline = support.core().get(caller, support.modelPath(caller, documentId, "/outline")).path("response");
        ObjectNode out = (ObjectNode) outline.deepCopy();
        out.put("url", support.documentUrl(caller, documentId));
        return support.text(out);
    }

    @McpTool(name = "validate_document", title = "문서 점검",
            description = "요구사항 추적 점검(반영 대기, 연결 끊김, 정리 필요, 근거 없는 테이블)과 DDL 생성 경고를 돌려준다. 물리명 규칙 같은 전체 설계 검증은 사용자가 Crowfoot 에디터의 검증 패널에서 본다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public String validateDocument(McpTransportContext context,
            @McpToolParam(description = "문서 ID") String documentId) {
        Caller caller = support.caller(context);
        JsonNode outline = support.core().get(caller, support.modelPath(caller, documentId, "/outline")).path("response");
        ObjectNode out = support.object();
        out.set("name", outline.path("name"));
        out.set("version", outline.path("version"));
        Map<String, ArrayNode> byState = new LinkedHashMap<>();
        for (String state : new String[] {"PENDING", "UNLINKED", "LEFTOVER", "DRAFT"}) {
            byState.put(state, support.object().arrayNode());
        }
        for (JsonNode requirement : outline.path("requirements")) {
            ArrayNode bucket = byState.get(requirement.path("state").asString(""));
            if (bucket != null) {
                ObjectNode item = bucket.addObject();
                item.set("code", requirement.path("code"));
                item.set("title", requirement.path("title"));
                item.set("tables", requirement.path("tables"));
            }
        }
        ObjectNode tracing = out.putObject("requirementTracing");
        tracing.set("pending — 확정했지만 ERD가 아직 반영하지 않은 요구사항", byState.get("PENDING"));
        tracing.set("unlinked — 연결된 테이블이 모두 사라진 요구사항", byState.get("UNLINKED"));
        tracing.set("leftover — 제외한 요구사항인데 테이블이 남아 있는 것", byState.get("LEFTOVER"));
        tracing.set("draft — 아직 확정하지 않은 요구사항", byState.get("DRAFT"));
        tracing.set("untracedTables — 근거 요구사항이 없는 테이블", outline.path("untracedTables"));
        JsonNode ddl = support.core().get(caller, support.modelPath(caller, documentId, "/ddl")).path("response");
        out.set("ddlWarnings", ddl.path("warnings"));
        out.put("note", "물리명 규칙, 외래 키 타입 불일치 같은 설계 검증 17종은 Crowfoot 에디터의 검증 패널에서 확인한다.");
        out.put("url", support.documentUrl(caller, documentId));
        return support.text(out);
    }

    @McpTool(name = "export_ddl", title = "DDL 내보내기",
            description = "문서를 대상 DBMS의 DDL 스크립트로 돌려준다. 마지막으로 저장한 내용 기준이다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public String exportDdl(McpTransportContext context,
            @McpToolParam(description = "문서 ID") String documentId) {
        Caller caller = support.caller(context);
        return support.text(support.core().get(caller, support.modelPath(caller, documentId, "/ddl")).path("response"));
    }

    @McpTool(name = "create_document", title = "빈 문서 만들기",
            description = "빈 ERD 문서를 만든다. 대상 DBMS는 이때만 정하고 나중에 바꿀 수 없다. 만든 뒤 save_requirements와 apply_schema로 채운다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public String createDocument(McpTransportContext context,
            @McpToolParam(description = "문서 이름(1~100자, 워크스페이스 안에서 유일)") String name,
            @McpToolParam(description = "대상 DBMS 코드 — get_design_context의 databaseTypes 가운데 하나(예: postgresql, mysql)") String databaseType,
            @McpToolParam(required = false, description = "설명(500자 이하)") String description) {
        Caller caller = support.caller(context);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("databaseType", databaseType);
        body.put("description", description);
        JsonNode model = support.core().post(caller, support.workspacePath(caller, "/models"), body).path("response");
        return support.text(created(caller, model));
    }

    @McpTool(name = "import_ddl", title = "DDL로 새 문서 만들기",
            description = "CREATE TABLE 스크립트로 새 ERD 문서를 만든다. 항상 새 문서를 만든다 — 기존 문서는 apply_schema로 고친다. "
                    + "previewOnly=true로 먼저 불러 읽은 테이블과 읽지 못한 문장을 확인한다. CREATE INDEX는 읽지 않는다. 요구사항 연결과 그룹은 만든 뒤 save_requirements·apply_schema로 한다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public String importDdl(McpTransportContext context,
            @McpToolParam(description = "DDL의 방언이자 새 문서의 대상 DBMS 코드(mysql 또는 postgresql)") String databaseType,
            @McpToolParam(description = "DDL 스크립트(CREATE TABLE 문, 1,000,000자 이하)") String ddl,
            @McpToolParam(required = false, description = "문서 이름(1~100자). previewOnly가 아니면 적는다") String name,
            @McpToolParam(required = false, description = "설명(500자 이하)") String description,
            @McpToolParam(required = false, description = "true면 읽은 결과만 돌려주고 문서를 만들지 않는다") Boolean previewOnly) {
        Caller caller = support.caller(context);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("databaseType", databaseType);
        body.put("ddl", ddl);
        if (Boolean.TRUE.equals(previewOnly)) {
            ObjectNode out = (ObjectNode) support.core().post(caller, support.workspacePath(caller, "/models/sql-import/preview"), body)
                    .path("response").deepCopy();
            out.put("created", false);
            return support.text(out);
        }
        body.put("name", name);
        body.put("description", description);
        JsonNode response = support.core().post(caller, support.workspacePath(caller, "/models/sql-import"), body).path("response");
        ObjectNode out = created(caller, response.path("model"));
        out.set("tableCount", response.path("tableCount"));
        out.set("relationshipCount", response.path("relationshipCount"));
        out.set("skipped", response.path("skipped"));
        return support.text(out);
    }

    private ObjectNode created(Caller caller, JsonNode model) {
        ObjectNode out = support.object();
        String id = model.path("modelId").asString();
        out.put("created", true);
        out.put("documentId", id);
        out.set("name", model.path("name"));
        out.set("databaseType", model.path("databaseType"));
        out.set("version", model.path("version"));
        out.put("url", support.documentUrl(caller, id));
        return out;
    }
}
