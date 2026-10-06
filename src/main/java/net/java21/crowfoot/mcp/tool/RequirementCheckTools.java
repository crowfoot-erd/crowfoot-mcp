package net.java21.crowfoot.mcp.tool;

import io.modelcontextprotocol.common.McpTransportContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.java21.crowfoot.mcp.auth.Caller;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 수용 기준을 데이터로 확인 (10-mcp/00-mcp-server.md Section 4.6 — DB 매니저 00-data-browser.md Section 3.9, v1.36).
 * 요구사항 수용 기준에 적어 둔 확인 SQL(SELECT 한 문장)을 문서가 연결된 데이터베이스에서 읽기 전용으로 실행하고 기대값과 견준다.
 * 확인 SQL은 save_requirements(criteria[].sql·expect)로 적는다. 데이터나 문서를 바꾸지 않는다.
 */
@Component
public class RequirementCheckTools {

    private final ToolSupport support;

    public RequirementCheckTools(ToolSupport support) {
        this.support = support;
    }

    @McpTool(name = "check_requirements", title = "수용 기준을 데이터로 확인",
            description = "요구사항 수용 기준의 확인 SQL을 문서가 연결된 데이터베이스에서 읽기 전용으로 실행해 기준마다 PASSED(기대값과 같다)·FAILED(다르다)·ERROR(실행하지 못했다)와 실제 값을 돌려준다. "
                    + "데이터와 문서를 바꾸지 않는다. 확인 SQL이 없는 기준은 건너뛴다(unchecked). "
                    + "확인 SQL은 save_requirements의 criteria[].sql·expect로 적는다 — 위반 건수를 세어 0을 기대하는 SELECT가 좋다(예: SELECT COUNT(*) FROM orders WHERE member_id IS NULL). "
                    + "FAILED가 있으면 사용자에게 어떤 기준이 데이터에서 지켜지지 않는지 보여 준다. 데이터를 고치는 일은 하지 않는다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public String checkRequirements(McpTransportContext context,
            @McpToolParam(description = "문서 ID") String documentId,
            @McpToolParam(required = false, description = "확인할 요구사항 코드(REQ-001 형식) 목록. 생략하면 확인 SQL이 있는 요구사항 전부") List<String> codes) {
        Caller caller = support.caller(context);
        JsonNode outline = support.core().get(caller, support.modelPath(caller, documentId, "/outline")).path("response");
        JsonNode source = outline.path("sourceConnectionId");
        if (source.isNull() || source.isMissingNode() || source.asString("").isEmpty()) {
            throw new IllegalStateException("이 문서는 데이터베이스에 연결돼 있지 않아 확인할 데이터가 없다. 먼저 plan_deployment와 deploy_document로 배포하거나, "
                    + "사용자가 Crowfoot 화면의 문서 목록에서 '데이터베이스에 연결'을 한다.");
        }
        String connectionId = support.id("sourceConnectionId", source.asString());
        Set<String> wanted = codes == null || codes.isEmpty() ? null : new HashSet<>(codes);

        // 확인 SQL이 있는 기준을 모은다 — key는 "코드/기준 id"
        ArrayNode checks = support.array();
        Map<String, JsonNode> criterionOf = new LinkedHashMap<>();
        Map<String, JsonNode> requirementOf = new LinkedHashMap<>();
        List<JsonNode> targets = new ArrayList<>();
        int unchecked = 0;
        for (JsonNode requirement : outline.path("requirements")) {
            String code = requirement.path("code").asString("");
            if (wanted != null && !wanted.contains(code)) {
                continue;
            }
            targets.add(requirement);
            for (JsonNode criterion : requirement.path("criteria")) {
                JsonNode check = criterion.path("check");
                if (!check.isObject() || check.path("sql").asString("").isBlank()) {
                    unchecked++;
                    continue;
                }
                String key = code + "/" + criterion.path("id").asString("");
                ObjectNode item = checks.addObject();
                item.put("key", key);
                item.put("sql", check.path("sql").asString());
                item.put("expect", check.path("expect").asString("0"));
                criterionOf.put(key, criterion);
                requirementOf.put(key, requirement);
            }
        }
        if (wanted != null) {
            Set<String> found = new HashSet<>();
            targets.forEach(requirement -> found.add(requirement.path("code").asString("")));
            List<String> missing = codes.stream().filter(code -> !found.contains(code)).toList();
            if (!missing.isEmpty()) {
                throw new IllegalArgumentException("문서에 없는 요구사항 코드다: " + String.join(", ", missing));
            }
        }

        ObjectNode out = support.object();
        out.set("documentName", outline.path("name"));
        out.put("connectionId", connectionId);
        out.put("url", support.documentUrl(caller, documentId));
        if (checks.isEmpty()) {
            out.put("checked", 0);
            out.put("unchecked", unchecked);
            out.set("requirements", support.array());
            out.put("next", "확인 SQL이 있는 수용 기준이 없다. 기준 문장을 보고 확인 SQL(위반 건수를 세는 SELECT와 기대값 0)을 제안하고, "
                    + "사용자가 확인하면 save_requirements의 criteria[].sql·expect로 적은 뒤 다시 부른다.");
            return support.text(out);
        }
        ObjectNode body = support.object();
        body.set("checks", checks);
        String path = "/database-manager/workspaces/" + caller.workspaceId() + "/connections/" + connectionId + "/checks";
        JsonNode response = support.core().postToDatabaseManager(caller, path, body).path("response");

        // 요구사항별로 묶어 돌려준다
        Map<String, ObjectNode> byCode = new LinkedHashMap<>();
        for (JsonNode result : response.path("results")) {
            String key = result.path("key").asString("");
            JsonNode requirement = requirementOf.get(key);
            if (requirement == null) {
                continue;
            }
            String code = requirement.path("code").asString("");
            ObjectNode group = byCode.computeIfAbsent(code, c -> {
                ObjectNode node = support.object();
                node.put("code", c);
                node.set("title", requirement.path("title"));
                node.putArray("criteria");
                return node;
            });
            ObjectNode item = ((ArrayNode) group.get("criteria")).addObject();
            item.set("text", criterionOf.get(key).path("text"));
            item.set("status", result.path("status"));
            item.set("value", result.path("value"));
            item.set("expect", result.path("expect"));
            if (!result.path("errorCode").isNull() && !result.path("errorCode").isMissingNode()) {
                item.set("errorCode", result.path("errorCode"));
                item.set("message", result.path("message"));
            }
        }
        out.put("checked", checks.size());
        out.put("unchecked", unchecked);
        out.set("passed", response.path("passed"));
        out.set("failed", response.path("failed"));
        out.set("errors", response.path("errors"));
        ArrayNode requirements = out.putArray("requirements");
        byCode.values().forEach(requirements::add);
        int failed = response.path("failed").asInt(0);
        int errors = response.path("errors").asInt(0);
        if (failed == 0 && errors == 0) {
            out.put("next", "확인한 수용 기준이 모두 데이터에서 지켜진다. 결과를 사용자에게 알린다.");
        } else {
            out.put("next", "FAILED(기대값과 다르다)와 ERROR(SQL을 실행하지 못했다)를 사용자에게 보여 준다. FAILED는 데이터가 기준을 어긴다는 뜻이다 — 데이터를 고치지 않는다. "
                    + "ERROR는 확인 SQL을 고쳐(save_requirements) 다시 확인할 수 있다.");
        }
        return support.text(out);
    }
}
