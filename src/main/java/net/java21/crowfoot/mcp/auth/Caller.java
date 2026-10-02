package net.java21.crowfoot.mcp.auth;

import io.modelcontextprotocol.common.McpTransportContext;

/**
 * 요청을 보낸 쪽 — Gateway가 토큰을 검증하고 넣어 준 헤더 값이다 (10-mcp/00-mcp-server.md Section 2).
 * 이 서버는 토큰 원문을 다루지 않는다. 받은 값을 core 호출에 그대로 붙인다.
 *
 * @param userId      토큰을 발급한 사용자 (X-USER-ID)
 * @param workspaceId 토큰이 묶인 워크스페이스 (X-TOKEN-WORKSPACE-ID) — 모든 도구가 이 워크스페이스에서만 동작한다
 * @param tokenId     토큰 ID (X-ACCESS-TOKEN-ID) — 없을 수 있다(로컬에서 헤더를 직접 넣을 때)
 */
public record Caller(String userId, String workspaceId, String tokenId) {

    public static final String HEADER_USER_ID = "X-USER-ID";
    public static final String HEADER_WORKSPACE_ID = "X-TOKEN-WORKSPACE-ID";
    public static final String HEADER_TOKEN_ID = "X-ACCESS-TOKEN-ID";

    /** 전송 계층이 요청 헤더에서 옮겨 둔 값으로 만든다 — 헤더가 없는 요청은 필터에서 이미 401로 끝난다 */
    public static Caller from(McpTransportContext context) {
        Object userId = context.get(HEADER_USER_ID);
        Object workspaceId = context.get(HEADER_WORKSPACE_ID);
        if (!(userId instanceof String user) || user.isBlank()
                || !(workspaceId instanceof String workspace) || workspace.isBlank()) {
            throw new IllegalStateException("Gateway를 거치지 않은 요청입니다 — 사용자·워크스페이스 헤더가 없습니다");
        }
        Object tokenId = context.get(HEADER_TOKEN_ID);
        return new Caller(user, workspace, tokenId instanceof String token && !token.isBlank() ? token : null);
    }
}
