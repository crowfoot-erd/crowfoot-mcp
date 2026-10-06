package net.java21.crowfoot.mcp.tool;

import io.modelcontextprotocol.common.McpTransportContext;
import java.util.LinkedHashMap;
import java.util.Map;
import net.java21.crowfoot.mcp.auth.Caller;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** 문제 신고 (10-mcp/00-mcp-server.md Section 4.5 — v1.34) */
@Component
public class SupportTools {

    private final ToolSupport support;

    public SupportTools(ToolSupport support) {
        this.support = support;
    }

    @McpTool(name = "report_bug", title = "버그 신고",
            description = "Crowfoot 도구를 쓰다 겪은 문제를 Crowfoot 커뮤니티의 \"제안 및 신고\" 게시판에 올린다. 작성자는 이 연결의 토큰을 발급한 사용자다. "
                    + "사용자에게 무엇을 신고할지 설명하고 동의를 받은 뒤에 부른다. 본문은 Markdown으로 요약, 재현 방법(부른 도구와 입력), 기대한 결과와 실제 결과를 쓴다. "
                    + "비밀번호·토큰 같은 비밀 값은 넣지 않는다. 돌려받은 url을 사용자에게 보여 준다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = true))
    public String reportBug(McpTransportContext context,
            @McpToolParam(description = "제목(200자 이하) — 무엇이 잘못됐는지 한 줄로") String title,
            @McpToolParam(description = "Markdown 본문(500,000자 이하)") String content,
            @McpToolParam(required = false, description = "관련 문서 ID(있으면)") String documentId,
            @McpToolParam(required = false, description = "문제가 난 도구 이름(예: plan_deployment)") String tool) {
        Caller caller = support.caller(context);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("content", content);
        if (documentId != null && !documentId.isBlank()) {
            body.put("documentId", support.id("documentId", documentId));
        }
        if (tool != null && !tool.isBlank()) {
            body.put("tool", tool);
        }
        JsonNode response = support.core().post(caller, support.workspacePath(caller, "/bug-reports"), body).path("response");
        ObjectNode out = support.object();
        out.put("reported", true);
        out.set("postId", response.path("postId"));
        out.set("title", response.path("title"));
        out.put("url", support.webUrl(response.path("path").asString("")));
        return support.text(out);
    }
}
