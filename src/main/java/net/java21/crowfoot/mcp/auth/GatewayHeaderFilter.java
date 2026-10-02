package net.java21.crowfoot.mcp.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gateway를 거치지 않은 요청을 거부한다 (10-mcp/00-mcp-server.md Section 2).
 * 인증은 Gateway에서 끝난다 — 여기서는 Gateway가 넣는 헤더가 있는지만 본다. 도구 목록 조회를 포함한 모든 MCP 요청이 대상이다.
 */
@Component
public class GatewayHeaderFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/mcp");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isBlank(request.getHeader(Caller.HEADER_USER_ID)) || isBlank(request.getHeader(Caller.HEADER_WORKSPACE_ID))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(
                    "{\"header\":{\"isSuccessful\":false,\"resultCode\":\"UNAUTHORIZED\",\"resultMessage\":\"Unauthorized\"}}");
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
