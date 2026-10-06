package net.java21.crowfoot.mcp.tool;

import io.modelcontextprotocol.common.McpTransportContext;
import java.util.LinkedHashMap;
import java.util.Map;
import net.java21.crowfoot.mcp.auth.Caller;
import net.java21.crowfoot.mcp.core.CoreException;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * DB → 문서 동기화 (10-mcp/00-mcp-server.md Section 4.4·6 — core 08-core/02-model.md Section 1.16).
 * 방향은 데이터베이스 → 문서다. 데이터베이스는 읽기만 하고 문서를 고친다(plan_migration·apply_migration의 반대 방향).
 * 계획 도구가 계획 지문을 돌려주고, 적용 도구는 그 값을 받아야 적용한다. 문서에만 있는 객체의 삭제(removals)는 기본으로 하지 않는다.
 */
// 빈 이름 syncTools는 Spring AI 자동 구성(StatelessToolCallbackConverterAutoConfiguration)이 쓰므로 클래스 이름을 피한다
@Component
public class DocumentSyncTools {

    private final ToolSupport support;

    public DocumentSyncTools(ToolSupport support) {
        this.support = support;
    }

    @McpTool(name = "plan_sync", title = "동기화 계획",
            description = "문서가 연결된 데이터베이스의 지금 구조를 읽어, 문서에 가져올 변경(items)과 문서에만 있어 지울 수 있는 것(removals)을 돌려준다. "
                    + "방향은 데이터베이스 → 문서다(데이터베이스는 바꾸지 않는다). 아무것도 바꾸지 않는다. "
                    + "데이터베이스가 정하는 값(타입, 길이, NULL, 기본값, 키, 외래 키, 인덱스, CHECK)은 데이터베이스를 따르고, "
                    + "문서에만 있는 값(테이블 위치, 색, 메모, 그룹, 요구사항, 설명, DB 코멘트가 없을 때의 논리명)은 그대로 둔다. "
                    + "결과를 사용자에게 보여 주고 승인을 받은 뒤 apply_sync를 부른다. removals는 따로 짚어서 보여 준다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public String planSync(McpTransportContext context,
            @McpToolParam(description = "문서 ID") String documentId) {
        Caller caller = support.caller(context);
        return support.text(plan(caller, documentId));
    }

    @McpTool(name = "apply_sync", title = "동기화 적용",
            description = "plan_sync의 계획대로 데이터베이스의 구조를 문서에 반영하고 새 문서 버전으로 저장한다. 사용자가 계획을 보고 승인한 뒤에만 부른다. "
                    + "planFingerprint는 plan_sync가 돌려준 값이다 — 그 사이 문서나 데이터베이스가 바뀌어 계획이 달라졌으면 적용하지 않고 새 계획을 돌려준다. "
                    + "removals(문서에만 있는 테이블·컬럼·관계·인덱스)는 기본으로 지우지 않는다. "
                    + "사용자가 removals를 보고 지우라고 명시적으로 승인했을 때만 includeRemovals=true를 넣는다. 문서 버전 기록에서 되돌릴 수 있다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = false, openWorldHint = false))
    public String applySync(McpTransportContext context,
            @McpToolParam(description = "문서 ID") String documentId,
            @McpToolParam(description = "plan_sync가 돌려준 planFingerprint") String planFingerprint,
            @McpToolParam(required = false, description = "문서에만 있는 객체(removals)까지 지우려면 true — 사용자가 removals를 보고 승인했을 때만. 생략하면 지우지 않는다") Boolean includeRemovals) {
        Caller caller = support.caller(context);
        String connectionId = sourceConnection(caller, documentId);
        if (planFingerprint == null || planFingerprint.isBlank()) {
            return notApplied("planFingerprint가 없다. plan_sync를 불러 계획을 사용자에게 보여 주고 승인받은 뒤 그 값을 넣는다.", null);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("planFingerprint", planFingerprint);
        body.put("includeRemovals", Boolean.TRUE.equals(includeRemovals));
        JsonNode response;
        try {
            response = support.core()
                    .post(caller, support.modelPath(caller, documentId, "/connections/" + connectionId + "/sync/apply"), body)
                    .path("response");
        } catch (CoreException e) {
            if (!"SYNC_PLAN_CHANGED".equals(e.resultCode())) {
                throw e;
            }
            return notApplied("사용자가 본 계획과 지금 계획이 다르다(문서나 데이터베이스가 그 사이에 바뀌었다). 이 새 계획을 사용자에게 다시 보여 주고 승인받는다.",
                    plan(caller, documentId));
        }
        ObjectNode out = (ObjectNode) response.deepCopy();
        out.put("applied", response.path("changed").asBoolean(false));
        out.put("url", support.documentUrl(caller, documentId));
        if (!response.path("changed").asBoolean(false)) {
            out.put("next", "반영할 변경이 없다. 문서가 이미 데이터베이스와 같다.");
        } else if (response.path("skippedRemovals").asInt(0) > 0) {
            out.put("next", "문서 주소(url)를 사용자에게 보여 준다. 문서에만 있는 객체 " + response.path("skippedRemovals").asInt()
                    + "건(removals)은 지우지 않았다. 지우려면 plan_sync를 다시 불러 removals를 보여 주고 승인을 받은 뒤 includeRemovals=true로 적용한다.");
        } else {
            out.put("next", "문서 주소(url)를 사용자에게 보여 준다. 사용자가 Crowfoot에서 결과를 확인한다.");
        }
        return support.text(out);
    }

    /* ---------- 내부 ---------- */

    private ObjectNode plan(Caller caller, String documentId) {
        JsonNode outline = support.core().get(caller, support.modelPath(caller, documentId, "/outline")).path("response");
        String connectionId = connectionOf(outline);
        JsonNode sync = support.core()
                .get(caller, support.modelPath(caller, documentId, "/connections/" + connectionId + "/sync")).path("response");
        ObjectNode out = support.object();
        out.set("documentName", outline.path("name"));
        out.set("documentVersion", sync.path("version"));
        out.put("connectionId", connectionId);
        out.put("direction", "database → document");
        out.set("changeCount", sync.path("changeCount"));
        out.set("items", sync.path("items"));
        out.set("removalCount", sync.path("removalCount"));
        out.set("removals", sync.path("removals"));
        out.set("planFingerprint", sync.path("planFingerprint"));
        out.put("url", support.documentUrl(caller, documentId));
        int changes = sync.path("changeCount").asInt(0);
        int removals = sync.path("removalCount").asInt(0);
        if (changes == 0 && removals == 0) {
            out.put("next", "문서가 이미 데이터베이스와 같다. 반영할 것이 없다.");
        } else if (removals == 0) {
            out.put("next", "items를 사용자에게 보여 주고 승인을 받는다. 승인하면 apply_sync에 planFingerprint를 그대로 넣어 부른다.");
        } else {
            out.put("next", "items를 사용자에게 보여 주고, removals(문서에만 있는 것)는 따로 짚어서 보여 준다. 승인하면 apply_sync에 planFingerprint를 넣어 부른다. "
                    + "removals는 기본으로 지우지 않는다 — 사용자가 지우라고 명시적으로 승인했을 때만 includeRemovals=true를 넣는다.");
        }
        return out;
    }

    private String sourceConnection(Caller caller, String documentId) {
        return connectionOf(support.core().get(caller, support.modelPath(caller, documentId, "/outline")).path("response"));
    }

    /** 문서의 원천 커넥션 — 동기화는 문서가 연결된 데이터베이스와만 한다 */
    private String connectionOf(JsonNode outline) {
        JsonNode source = outline.path("sourceConnectionId");
        if (source.isNull() || source.isMissingNode() || source.asString("").isEmpty()) {
            throw new IllegalStateException("이 문서는 데이터베이스에 연결돼 있지 않아 동기화할 데이터베이스가 없다. "
                    + "이미 있는 데이터베이스에서 문서를 새로 만들려면 사용자가 Crowfoot 화면에서 리버스(데이터베이스에서 문서 만들기)를 하거나, "
                    + "문서 목록에서 '데이터베이스에 연결'을 한다.");
        }
        return support.id("sourceConnectionId", source.asString());
    }

    private String notApplied(String reason, ObjectNode plan) {
        ObjectNode out = support.object();
        out.put("applied", false);
        out.put("reason", reason);
        if (plan != null) {
            out.set("plan", plan);
        }
        return support.text(out);
    }
}
