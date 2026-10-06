package net.java21.crowfoot.mcp.tool;

import io.modelcontextprotocol.common.McpTransportContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.java21.crowfoot.mcp.auth.Caller;
import net.java21.crowfoot.mcp.core.CoreException;
import net.java21.crowfoot.mcp.tool.input.Inputs.RequirementItem;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 요구사항 동기화 (10-mcp/00-mcp-server.md Section 4.6 — core 08-core/17-model-edit.md Section 3.5, v1.36).
 * 기준이 되는 요구사항 전체 목록(회의록·기획 문서에서 정리한 것)으로 문서의 요구사항을 맞춘다.
 * 계획 도구가 추가·수정·빠짐을 보여 주고 계획 지문을 돌려준다. 적용 도구는 그 값을 받아야 적용한다.
 */
@Component
public class RequirementSyncTools {

    private final ToolSupport support;

    public RequirementSyncTools(ToolSupport support) {
        this.support = support;
    }

    @McpTool(name = "plan_requirements_sync", title = "요구사항 동기화 계획",
            description = "기준이 되는 요구사항 전체 목록을 문서의 요구사항과 맞춰 본다. 아무것도 바꾸지 않는다. "
                    + "code가 있으면 code로, 없으면 제목으로 맞춘다. 맞춘 요구사항은 준 필드만 비교한다(주지 않은 필드는 그대로 둔다). "
                    + "결과는 added(새로 만들 것), updated(필드별 전후), missing(목록에 없는 문서 요구사항), unchanged다. "
                    + "missing은 기본으로 dropped로 바꾸고, acceptRemovals=true면 지운다. "
                    + "결과를 사용자에게 보여 주고 승인을 받은 뒤 apply_requirements_sync를 같은 목록으로 부른다. missing은 따로 짚어서 보여 준다. "
                    + "목록 일부만 고치려면 이 도구 대신 save_requirements를 쓴다 — 이 도구는 목록에 없는 요구사항을 빠짐으로 본다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public String planRequirementsSync(McpTransportContext context,
            @McpToolParam(description = "문서 ID") String documentId,
            @McpToolParam(description = "기준이 되는 요구사항 전체 목록(1~200개). 항목 형식은 save_requirements와 같다") List<RequirementItem> items,
            @McpToolParam(required = false, description = "목록에 없는 요구사항을 지우려면 true — 사용자가 승인했을 때만. 생략하면 dropped로 바꾼다") Boolean acceptRemovals) {
        Caller caller = support.caller(context);
        return support.text(plan(caller, documentId, items, acceptRemovals));
    }

    @McpTool(name = "apply_requirements_sync", title = "요구사항 동기화 적용",
            description = "plan_requirements_sync의 계획대로 요구사항을 맞추고 새 문서 버전으로 저장한다. 사용자가 계획을 보고 승인한 뒤에만 부른다. "
                    + "items와 acceptRemovals는 계획을 만들 때와 같은 값을 넣는다. planFingerprint는 계획 도구가 돌려준 값이다 — "
                    + "그 사이 문서가 바뀌어 계획이 달라졌으면 적용하지 않고 새 계획을 돌려준다. "
                    + "제목이나 내용이 바뀐 요구사항은 '반영 대기'가 된다. 이어서 get_document로 바뀐 내용(changes)을 보고 apply_schema → plan_migration → 승인 → apply_migration으로 ERD와 데이터베이스까지 반영한다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = false, openWorldHint = false))
    public String applyRequirementsSync(McpTransportContext context,
            @McpToolParam(description = "문서 ID") String documentId,
            @McpToolParam(description = "계획을 만들 때와 같은 요구사항 전체 목록") List<RequirementItem> items,
            @McpToolParam(description = "plan_requirements_sync가 돌려준 planFingerprint") String planFingerprint,
            @McpToolParam(required = false, description = "계획을 만들 때와 같은 값 — 목록에 없는 요구사항을 지우려면 true") Boolean acceptRemovals) {
        Caller caller = support.caller(context);
        if (planFingerprint == null || planFingerprint.isBlank()) {
            return notApplied("planFingerprint가 없다. plan_requirements_sync를 불러 계획을 사용자에게 보여 주고 승인받은 뒤 그 값을 넣는다.", null);
        }
        Map<String, Object> body = body(items, acceptRemovals);
        body.put("planFingerprint", planFingerprint);
        body.put("note", support.note("요구사항 동기화 " + (items == null ? 0 : items.size()) + "건"));
        JsonNode response;
        try {
            response = support.core()
                    .post(caller, support.modelPath(caller, documentId, "/requirements/sync/apply"), body).path("response");
        } catch (CoreException e) {
            if (!"REQUIREMENTS_SYNC_PLAN_CHANGED".equals(e.resultCode()) && !"VERSION_CONFLICT".equals(e.resultCode())) {
                throw e;
            }
            return notApplied("사용자가 본 계획과 지금 계획이 다르다(문서가 그 사이에 바뀌었다). 이 새 계획을 사용자에게 다시 보여 주고 승인받는다.",
                    plan(caller, documentId, items, acceptRemovals));
        }
        JsonNode result = response.path("result");
        ObjectNode out = support.object();
        out.put("applied", result.path("changed").asBoolean(false));
        out.set("documentVersion", result.path("version"));
        out.set("added", response.path("added"));
        out.set("updated", response.path("updated"));
        out.set("dropped", response.path("dropped"));
        out.set("removed", response.path("removed"));
        out.set("requirements", result.path("requirements"));
        out.put("url", support.documentUrl(caller, documentId));
        out.put("next", result.path("changed").asBoolean(false)
                ? "문서 주소(url)를 사용자에게 보여 준다. 반영 대기 요구사항이 있으면 get_document로 바뀐 내용(changes)을 확인하고 apply_schema로 ERD를 고친다."
                : "바뀐 것이 없다. 문서의 요구사항이 이미 목록과 같다.");
        return support.text(out);
    }

    /* ---------- 내부 ---------- */

    private ObjectNode plan(Caller caller, String documentId, List<RequirementItem> items, Boolean acceptRemovals) {
        JsonNode plan = support.core()
                .post(caller, support.modelPath(caller, documentId, "/requirements/sync"), body(items, acceptRemovals))
                .path("response");
        ObjectNode out = (ObjectNode) plan.deepCopy();
        out.remove("workspaceId");
        out.remove("modelId");
        out.put("url", support.documentUrl(caller, documentId));
        int changes = plan.path("changeCount").asInt(0);
        boolean anyMissing = false;
        for (JsonNode missing : plan.path("missing")) {
            anyMissing |= !"none".equals(missing.path("action").asString(""));
        }
        if (changes == 0) {
            out.put("next", "문서의 요구사항이 이미 목록과 같다. 반영할 것이 없다.");
        } else if (!anyMissing) {
            out.put("next", "added와 updated를 사용자에게 보여 주고 승인을 받는다. 승인하면 apply_requirements_sync에 같은 items와 planFingerprint를 넣어 부른다.");
        } else {
            out.put("next", "added와 updated를 보여 주고, missing(목록에 없는 문서 요구사항)은 따로 짚어서 보여 준다 — action이 drop이면 dropped로 바꾸고 remove면 지운다. "
                    + "승인하면 apply_requirements_sync에 같은 items·acceptRemovals와 planFingerprint를 넣어 부른다.");
        }
        return out;
    }

    private static Map<String, Object> body(List<RequirementItem> items, Boolean acceptRemovals) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("acceptRemovals", Boolean.TRUE.equals(acceptRemovals));
        return body;
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
