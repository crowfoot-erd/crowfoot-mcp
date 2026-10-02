package net.java21.crowfoot.mcp.tool;

import io.modelcontextprotocol.common.McpTransportContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import net.java21.crowfoot.mcp.auth.Caller;
import net.java21.crowfoot.mcp.core.CoreException;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 데이터베이스 발급·배포·변경 반영 (10-mcp/00-mcp-server.md Section 4.4·6).
 * 실행은 되돌릴 수 없다. 계획 도구가 문서 버전과 계획 지문을 돌려주고, 실행 도구는 그 값을 받아야 실행한다.
 * 허용되지 않은 커넥션에 대한 실행은 core가 거부한다(08-core/06-connection.md Section 2.1) — 여기서는 계획에 미리 알려 준다.
 */
@Component
public class DatabaseTools {

    private static final String NOT_ALLOWED = "이 커넥션은 MCP 반영이 허용되지 않았다. 사용자가 Crowfoot 화면에서 직접 반영하거나, "
            + "워크스페이스의 데이터베이스 탭에서 이 커넥션의 'MCP 반영 허용'을 켜야 한다. MCP로는 이 설정을 바꿀 수 없다.";

    private final ToolSupport support;

    public DatabaseTools(ToolSupport support) {
        this.support = support;
    }

    @McpTool(name = "list_databases", title = "매니지드 데이터베이스 목록",
            description = "Crowfoot이 내어 주는 매니지드 데이터베이스의 발급 목록과 인스턴스별 남은 한도. 발급한 데이터베이스는 커넥션으로 등록돼 있다(connectionId).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public String listDatabases(McpTransportContext context) {
        Caller caller = support.caller(context);
        JsonNode body = support.core().get(caller, support.workspacePath(caller, "/managed-databases"));
        ObjectNode out = support.object();
        out.set("issued", body.path("responses"));
        out.set("instances", body.path("limitSummary"));
        return support.text(out);
    }

    @McpTool(name = "issue_database", title = "매니지드 데이터베이스 발급",
            description = "매니지드 데이터베이스를 하나 발급받는다. 전용 스키마와 전용 계정이 만들어지고 커넥션으로 등록된다. "
                    + "사용자가 승인한 뒤에만 부른다. 문서의 대상 DBMS와 같은 종류의 인스턴스를 고른다(list_databases의 instances). "
                    + "접속 정보(비밀번호)는 돌려주지 않는다 — 사용자가 Crowfoot의 데이터베이스 탭에서 본다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public String issueDatabase(McpTransportContext context,
            @McpToolParam(description = "발급할 인스턴스 ID(list_databases의 instances[].instanceId)") String instanceId) {
        Caller caller = support.caller(context);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("instanceId", Long.parseLong(support.id("instanceId", instanceId)));
        JsonNode issued = support.core().post(caller, support.workspacePath(caller, "/managed-databases"), body).path("response");
        ObjectNode out = (ObjectNode) issued.deepCopy();
        out.put("next", "plan_deployment에 이 connectionId를 넣어 배포 계획을 확인한다.");
        return support.text(out);
    }

    @McpTool(name = "list_connections", title = "커넥션 목록",
            description = "워크스페이스에 등록된 데이터베이스 커넥션. 이름, DBMS, 매니지드 여부, MCP 반영 허용 여부를 돌려준다. 접속 정보는 돌려주지 않는다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public String listConnections(McpTransportContext context) {
        Caller caller = support.caller(context);
        ArrayNode connections = support.object().arrayNode();
        for (JsonNode connection : support.core().get(caller, support.workspacePath(caller, "/connections")).path("responses")) {
            connections.add(connectionSummary(connection));
        }
        ObjectNode out = support.object();
        out.set("connections", connections);
        return support.text(out);
    }

    @McpTool(name = "plan_deployment", title = "배포 계획",
            description = "문서를 데이터베이스에 처음 배포할 때 실행될 DDL과 대상을 돌려준다. 아무것도 실행하지 않는다. "
                    + "결과를 사용자에게 보여 주고 승인을 받은 뒤 deploy_document를 부른다. 빈 데이터베이스에 대한 첫 배포에만 쓴다 — 이미 배포한 문서의 변경은 plan_migration을 쓴다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public String planDeployment(McpTransportContext context,
            @McpToolParam(description = "문서 ID") String documentId,
            @McpToolParam(description = "대상 커넥션 ID(list_connections 또는 issue_database의 connectionId)") String connectionId) {
        Caller caller = support.caller(context);
        JsonNode outline = outline(caller, documentId);
        JsonNode connection = findConnection(caller, support.id("connectionId", connectionId));
        JsonNode ddl = support.core().get(caller, support.modelPath(caller, documentId, "/ddl")).path("response");
        ObjectNode out = support.object();
        out.set("documentName", outline.path("name"));
        out.set("documentVersion", outline.path("version"));
        ObjectNode target = connectionSummary(connection);
        out.set("target", target);
        boolean sameDbms = outline.path("databaseType").asString("").equals(connection.path("dbmsType").asString(""));
        out.put("executable", sameDbms && target.path("mcpApplyAllowed").asBoolean(false));
        if (!sameDbms) {
            out.put("blocked", "문서의 대상 DBMS(" + outline.path("databaseType").asString() + ")와 커넥션의 DBMS("
                    + connection.path("dbmsType").asString() + ")가 다르다. 같은 종류의 커넥션을 고른다.");
        } else if (!target.path("mcpApplyAllowed").asBoolean(false)) {
            out.put("blocked", NOT_ALLOWED);
        }
        out.set("sql", ddl.path("sql"));
        out.set("tableCount", ddl.path("tableCount"));
        out.set("relationshipCount", ddl.path("relationshipCount"));
        out.set("warnings", ddl.path("warnings"));
        out.put("next", "대상과 SQL을 사용자에게 보여 주고 승인을 받는다. 승인하면 deploy_document에 documentVersion을 그대로 넣어 부른다. 실행은 되돌릴 수 없다.");
        return support.text(out);
    }

    @McpTool(name = "deploy_document", title = "배포",
            description = "문서의 DDL을 데이터베이스에서 실행하고 문서를 그 커넥션에 연결한다. 되돌릴 수 없다. "
                    + "plan_deployment의 결과를 사용자에게 보여 주고 승인을 받은 뒤에만 부른다. documentVersion은 plan_deployment가 돌려준 값이다 — 그 뒤에 문서가 바뀌었으면 실행하지 않는다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = false, openWorldHint = true))
    public String deployDocument(McpTransportContext context,
            @McpToolParam(description = "문서 ID") String documentId,
            @McpToolParam(description = "대상 커넥션 ID") String connectionId,
            @McpToolParam(description = "plan_deployment가 돌려준 documentVersion") Long documentVersion) {
        Caller caller = support.caller(context);
        String connection = support.id("connectionId", connectionId);
        JsonNode outline = outline(caller, documentId);
        if (documentVersion == null || outline.path("version").asLong(-1) != documentVersion) {
            return notExecuted("사용자가 계획을 본 뒤에 문서가 바뀌었다(지금 버전 " + outline.path("version").asLong()
                    + "). plan_deployment를 다시 불러 사용자에게 확인받는다.");
        }
        Map<String, Object> body = Map.of("connectionId", connection);
        ObjectNode out = (ObjectNode) support.core().post(caller, support.modelPath(caller, documentId, "/deploy"), body).path("response").deepCopy();
        out.put("executed", true);
        // 문서를 커넥션에 연결한다 — 이후 plan_migration이 이 커넥션과 비교한다. 이미 연결된 문서면 그대로 둔다
        if (outline.path("sourceConnectionId").isNull() || outline.path("sourceConnectionId").isMissingNode()) {
            try {
                support.core().post(caller, support.modelPath(caller, documentId, "/connections"), body);
                out.put("connected", true);
            } catch (CoreException e) {
                out.put("connected", false);
                out.put("connectNote", "배포는 했지만 문서를 커넥션에 연결하지 못했다: " + e.getMessage());
            }
        }
        if (out.path("failedCount").asInt(0) > 0) {
            out.put("next", "실패한 문장이 있다. statements에서 error를 확인해 사용자에게 알린다. 이미 있는 객체와 부딪힌 경우가 많다.");
        }
        out.put("url", support.documentUrl(caller, documentId));
        return support.text(out);
    }

    @McpTool(name = "plan_migration", title = "변경 계획",
            description = "문서가 연결된 데이터베이스의 지금 구조와 문서를 비교해, 바뀐 부분만 ALTER 문으로 돌려준다. 아무것도 실행하지 않는다. "
                    + "결과를 사용자에게 보여 주고 승인을 받은 뒤 apply_migration을 부른다. DESTRUCTIVE 경고가 있으면 삭제 문장을 따로 짚어서 보여 준다. "
                    + "물리명을 바꾼 테이블·컬럼은 삭제 후 추가로 나오고 그 데이터가 사라진다 — 삭제와 추가가 함께 있으면 이름 변경인지 사용자에게 확인한다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public String planMigration(McpTransportContext context,
            @McpToolParam(description = "문서 ID") String documentId) {
        Caller caller = support.caller(context);
        return support.text(migrationPlan(caller, documentId));
    }

    @McpTool(name = "apply_migration", title = "변경 반영",
            description = "plan_migration의 변경 계획을 데이터베이스에서 실행한다. 되돌릴 수 없다. 사용자가 계획을 보고 승인한 뒤에만 부른다. "
                    + "planFingerprint와 documentVersion은 plan_migration이 돌려준 값이다 — 그 사이 문서나 데이터베이스가 바뀌었으면 실행하지 않고 새 계획을 돌려준다. "
                    + "계획에 DESTRUCTIVE 경고가 있으면 사용자가 삭제 문장을 명시적으로 승인했을 때만 acceptDestructive=true를 넣는다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = false, openWorldHint = true))
    public String applyMigration(McpTransportContext context,
            @McpToolParam(description = "문서 ID") String documentId,
            @McpToolParam(description = "plan_migration이 돌려준 planFingerprint") String planFingerprint,
            @McpToolParam(description = "plan_migration이 돌려준 documentVersion") Long documentVersion,
            @McpToolParam(required = false, description = "삭제 문장(DESTRUCTIVE)을 사용자가 승인했으면 true") Boolean acceptDestructive) {
        Caller caller = support.caller(context);
        ObjectNode plan = migrationPlan(caller, documentId);
        if (documentVersion == null || plan.path("documentVersion").asLong(-1) != documentVersion
                || planFingerprint == null || !planFingerprint.equals(plan.path("planFingerprint").asString())) {
            plan.put("executed", false);
            plan.put("reason", "사용자가 본 계획과 지금 계획이 다르다(문서나 데이터베이스가 그 사이에 바뀌었다). 이 새 계획을 사용자에게 다시 보여 주고 승인받는다.");
            return support.text(plan);
        }
        if (plan.path("statementCount").asInt(0) == 0) {
            return notExecuted("반영할 변경이 없다. 문서와 데이터베이스가 같다.");
        }
        if (!plan.path("executable").asBoolean(false)) {
            return notExecuted(plan.path("blocked").asString(NOT_ALLOWED));
        }
        if (plan.path("destructive").asBoolean(false) && !Boolean.TRUE.equals(acceptDestructive)) {
            return notExecuted("계획에 삭제 문장(DESTRUCTIVE)이 있다. 삭제 문장을 사용자에게 짚어서 보여 주고 승인을 받은 뒤 acceptDestructive=true로 다시 부른다.");
        }
        String connectionId = plan.path("target").path("connectionId").asString();
        ObjectNode out = (ObjectNode) support.core()
                .post(caller, support.modelPath(caller, documentId, "/connections/" + connectionId + "/migration/execute"), null)
                .path("response").deepCopy();
        out.put("executed", true);
        if (out.path("failedCount").asInt(0) > 0) {
            out.put("next", "실패한 문장이 있다. 나머지는 실행됐다. plan_migration을 다시 불러 남은 차이를 확인한다.");
        }
        return support.text(out);
    }

    /* ---------- 내부 ---------- */

    private JsonNode outline(Caller caller, String documentId) {
        return support.core().get(caller, support.modelPath(caller, documentId, "/outline")).path("response");
    }

    private ObjectNode migrationPlan(Caller caller, String documentId) {
        JsonNode outline = outline(caller, documentId);
        JsonNode source = outline.path("sourceConnectionId");
        if (source.isNull() || source.isMissingNode() || source.asString("").isEmpty()) {
            throw new IllegalStateException("이 문서는 데이터베이스에 연결돼 있지 않다. 처음 배포는 plan_deployment와 deploy_document로 한다. "
                    + "이미 있는 데이터베이스에 연결하려면 사용자가 Crowfoot 화면의 문서 목록에서 '데이터베이스에 연결'을 한다.");
        }
        String connectionId = source.asString();
        JsonNode connection = findConnection(caller, connectionId);
        JsonNode migration = support.core()
                .get(caller, support.modelPath(caller, documentId, "/connections/" + connectionId + "/migration")).path("response");
        ObjectNode plan = support.object();
        plan.set("documentName", outline.path("name"));
        plan.set("documentVersion", outline.path("version"));
        ObjectNode target = connectionSummary(connection);
        plan.set("target", target);
        String sql = migration.path("sql").asString("");
        plan.put("planFingerprint", sha256(sql));
        plan.set("statementCount", migration.path("statementCount"));
        plan.put("sql", sql);
        plan.set("warnings", migration.path("warnings"));
        boolean destructive = false;
        for (JsonNode warning : migration.path("warnings")) {
            destructive |= "DESTRUCTIVE".equals(warning.path("code").asString(""));
        }
        plan.put("destructive", destructive);
        boolean allowed = target.path("mcpApplyAllowed").asBoolean(false);
        plan.put("executable", allowed);
        if (!allowed) {
            plan.put("blocked", NOT_ALLOWED);
        }
        plan.put("next", "SQL을 사용자에게 보여 주고 승인을 받는다. 승인하면 apply_migration에 planFingerprint와 documentVersion을 그대로 넣어 부른다. 실행은 되돌릴 수 없다.");
        return plan;
    }

    private JsonNode findConnection(Caller caller, String connectionId) {
        for (JsonNode connection : support.core().get(caller, support.workspacePath(caller, "/connections")).path("responses")) {
            if (connectionId.equals(connection.path("connectionId").asString())) {
                return connection;
            }
        }
        throw new IllegalArgumentException("커넥션 " + connectionId + "를 이 워크스페이스에서 찾지 못했다. list_connections로 확인한다.");
    }

    /** 접속 정보(호스트, 계정)를 뺀 요약 — mcpApplyAllowed는 매니지드이거나 설정이 켜진 커넥션이면 true */
    private ObjectNode connectionSummary(JsonNode connection) {
        ObjectNode out = support.object();
        out.put("connectionId", connection.path("connectionId").asString());
        out.set("name", connection.path("name"));
        out.set("dbmsType", connection.path("dbmsType"));
        out.set("databaseName", connection.path("databaseName"));
        if (connection.hasNonNull("schemaName")) {
            out.set("schemaName", connection.path("schemaName"));
        }
        boolean managed = connection.path("managed").asBoolean(false);
        out.put("managed", managed);
        out.put("mcpApplyAllowed", managed || connection.path("mcpApplyAllowed").asBoolean(false));
        return out;
    }

    private String notExecuted(String reason) {
        ObjectNode out = support.object();
        out.put("executed", false);
        out.put("reason", reason);
        return support.text(out);
    }

    /** 계획 지문 — 변경 계획 SQL 전문의 SHA-256(16진수) */
    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
