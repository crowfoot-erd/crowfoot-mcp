package net.java21.crowfoot.mcp.tool;

import io.modelcontextprotocol.common.McpTransportContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.java21.crowfoot.mcp.auth.Caller;
import net.java21.crowfoot.mcp.tool.input.Inputs.AreaInput;
import net.java21.crowfoot.mcp.tool.input.Inputs.CheckRef;
import net.java21.crowfoot.mcp.tool.input.Inputs.ColumnRef;
import net.java21.crowfoot.mcp.tool.input.Inputs.IndexRef;
import net.java21.crowfoot.mcp.tool.input.Inputs.RelationshipInput;
import net.java21.crowfoot.mcp.tool.input.Inputs.RelationshipRef;
import net.java21.crowfoot.mcp.tool.input.Inputs.RequirementItem;
import net.java21.crowfoot.mcp.tool.input.Inputs.TableInput;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 요구사항과 ERD 고치기 (10-mcp/00-mcp-server.md Section 4.3).
 * 입력을 문서 편집 API의 요청으로 옮긴다(08-core/17-model-edit.md Section 3). baseVersion은 넣지 않는다 —
 * 편집 API가 최신 본체에 적용한다. 입력이 이름 기준이라 그 사이에 문서가 바뀌어도 같은 요청을 적용할 수 있다.
 */
@Component
public class EditTools {

    private final ToolSupport support;

    public EditTools(ToolSupport support) {
        this.support = support;
    }

    @McpTool(name = "save_requirements", title = "요구사항 등록·수정",
            description = "요구사항을 문서에 등록하거나 고친다. 사용자가 확인한 요구사항만 등록한다. code가 있으면 그 요구사항을 고치고(준 필드만), 없으면 새로 만든다. "
                    + "제목이나 내용이 바뀌면 그 요구사항은 '반영 대기'가 된다 — 이어서 apply_schema로 ERD를 고치고 requirementCodes로 연결하면 반영된다. "
                    + "빠진 요구사항은 지우지 않고 status를 dropped로 바꾼다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public String saveRequirements(McpTransportContext context,
            @McpToolParam(description = "문서 ID") String documentId,
            @McpToolParam(description = "요구사항 항목(1~200개)") List<RequirementItem> items) {
        Caller caller = support.caller(context);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("note", support.note("요구사항 " + (items == null ? 0 : items.size()) + "건 반영"));
        return result(caller, documentId, support.core().post(caller, support.modelPath(caller, documentId, "/requirements/apply"), body));
    }

    @McpTool(name = "apply_schema", title = "ERD 등록·수정",
            description = "테이블, 컬럼, 키, 관계, 그룹을 문서에 등록하거나 고친다. 물리명이 같은 테이블·컬럼은 고치고 없는 것은 만든다. 입력에 없는 것은 그대로 둔다 — 지우지 않는다(삭제는 remove_objects). "
                    + "테이블마다 requirementCodes로 근거 요구사항을 연결한다. 근거가 없으면 save_requirements로 요구사항을 먼저 등록한다. "
                    + "외래 키 컬럼, 키 이름, 외래 키 인덱스, 테이블 위치는 Crowfoot이 만든다. 기존 문서는 get_document로 먼저 읽고 바뀌는 부분만 보낸다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public String applySchema(McpTransportContext context,
            @McpToolParam(description = "문서 ID") String documentId,
            @McpToolParam(required = false, description = "테이블(100개 이하)") List<TableInput> tables,
            @McpToolParam(required = false, description = "관계. 부모와 자식이 같은 관계가 있으면 고치고 없으면 만든다. 부모와 자식이 같은 관계가 여럿이면 name(외래 키 이름 — get_document의 관계 name)으로 고른다") List<RelationshipInput> relationships,
            @McpToolParam(required = false, description = "그룹(도메인). 이름이 같은 그룹이 있으면 고치고 없으면 만든다. 그룹이 없는 테이블은 requirementCodes로 연결한 요구사항의 도메인 그룹에 저절로 들어간다 — 다른 그룹에 넣을 때만 여기에 적는다") List<AreaInput> areas) {
        Caller caller = support.caller(context);
        Map<String, Object> body = new LinkedHashMap<>();
        putIfPresent(body, "tables", tables);
        putIfPresent(body, "relationships", relationships);
        putIfPresent(body, "areas", areas);
        body.put("note", support.note("ERD 반영 — 테이블 " + size(tables) + ", 관계 " + size(relationships) + ", 그룹 " + size(areas)));
        return result(caller, documentId, support.core().post(caller, support.modelPath(caller, documentId, "/schema/apply"), body));
    }

    @McpTool(name = "remove_objects", title = "삭제",
            description = "테이블, 컬럼, 관계, CHECK 제약, 인덱스, 요구사항을 문서에서 지운다. 사용자가 명시적으로 지우라고 했을 때만 부른다. "
                    + "테이블을 지우면 붙은 관계와 상대 테이블의 외래 키 컬럼이 함께 지워진다. 관계를 지우면 그 관계가 만든 외래 키 컬럼이 지워진다. "
                    + "컬럼을 지우면 그 컬럼을 쓰는 CHECK 제약도 지워진다 — 지운 CHECK는 warnings(CHECK_REMOVED_WITH_COLUMN)로 알려 주니 사용자에게 그대로 보여 준다. "
                    + "빠진 요구사항은 지우지 말고 save_requirements로 status를 dropped로 바꾼다 — 요구사항 삭제는 잘못 등록한 항목에만 쓴다. 문서 자체는 지울 수 없다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = false, openWorldHint = false))
    public String removeObjects(McpTransportContext context,
            @McpToolParam(description = "문서 ID") String documentId,
            @McpToolParam(required = false, description = "지울 테이블의 물리명") List<String> tables,
            @McpToolParam(required = false, description = "지울 컬럼") List<ColumnRef> columns,
            @McpToolParam(required = false, description = "지울 관계") List<RelationshipRef> relationships,
            @McpToolParam(required = false, description = "지울 CHECK 제약") List<CheckRef> checks,
            @McpToolParam(required = false, description = "지울 인덱스. 인덱스의 컬럼만 바꿀 때는 지우지 말고 apply_schema에 같은 이름으로 보낸다") List<IndexRef> indexes,
            @McpToolParam(required = false, description = "지울 요구사항의 코드") List<String> requirements) {
        Caller caller = support.caller(context);
        Map<String, Object> body = new LinkedHashMap<>();
        putIfPresent(body, "tables", tables);
        putIfPresent(body, "columns", columns);
        putIfPresent(body, "relationships", relationships);
        putIfPresent(body, "checks", checks);
        putIfPresent(body, "indexes", indexes);
        putIfPresent(body, "requirements", requirements);
        body.put("note", support.note("삭제 — 테이블 " + size(tables) + ", 컬럼 " + size(columns) + ", 관계 " + size(relationships)
                + ", CHECK " + size(checks) + ", 인덱스 " + size(indexes) + ", 요구사항 " + size(requirements)));
        return result(caller, documentId, support.core().post(caller, support.modelPath(caller, documentId, "/schema/remove"), body));
    }

    @McpTool(name = "register_site", title = "만든 사이트 등록",
            description = "이 ERD 문서로 만든 사이트의 주소를 등록하거나 고친다(문서당 한 곳). 사용자가 사이트 주소를 알려 주면 부른다. "
                    + "Crowfoot이 사이트 화면을 찍어 썸네일, 제목, 설명을 채우고 시작 페이지와 사이트 쇼케이스(/showcase)에 바로 보여 준다 — 승인 절차가 없다. "
                    + "찍는 데 최대 30초쯤 걸린다. 결과의 captureError가 있으면 찍지 못한 사유이니 사용자에게 알린다(등록은 됐다). "
                    + "내부망·로컬 주소는 등록할 수 없다. 같은 주소로 다시 부르면 제목·설명만 고친다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = true))
    public String registerSite(McpTransportContext context,
            @McpToolParam(description = "문서 ID") String documentId,
            @McpToolParam(description = "사이트 주소(http 또는 https, 2,000자 이하)") String url,
            @McpToolParam(required = false, description = "카드 제목(200자 이하). 생략하면 사이트의 제목을 쓴다") String title,
            @McpToolParam(required = false, description = "한 줄 소개(500자 이하). 생략하면 사이트의 설명을 쓴다") String description) {
        Caller caller = support.caller(context);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("url", url);
        if (title != null && !title.isBlank()) {
            body.put("title", title);
        }
        if (description != null) {
            body.put("description", description);
        }
        JsonNode site = support.core().put(caller, support.modelPath(caller, documentId, "/site"), body).path("response");
        ObjectNode out = support.object();
        out.put("registered", true);
        out.set("siteUrl", site.path("url"));
        out.set("title", site.path("title"));
        out.set("description", site.path("description"));
        out.put("thumbnail", site.path("thumbnailUrl").isString());
        out.set("captureError", site.path("captureError"));
        out.put("url", support.documentUrl(caller, documentId));
        out.put("showcaseUrl", support.webUrl("/showcase"));
        return support.text(out);
    }

    /** 편집 API의 응답에 문서 주소와 다음에 할 일을 더한다 */
    private String result(Caller caller, String documentId, JsonNode body) {
        ObjectNode out = (ObjectNode) body.path("response").deepCopy();
        out.put("url", support.documentUrl(caller, documentId));
        JsonNode pending = out.path("requirements").path("pending");
        if (pending.isArray() && !pending.isEmpty()) {
            out.put("next", "반영 대기 요구사항이 남아 있다. ERD를 고쳐 apply_schema의 requirementCodes로 연결하거나, 사용자에게 알린다.");
        }
        return support.text(out);
    }

    private static void putIfPresent(Map<String, Object> body, String name, List<?> value) {
        if (value != null && !value.isEmpty()) {
            body.put(name, value);
        }
    }

    private static int size(List<?> value) {
        return value == null ? 0 : value.size();
    }
}
