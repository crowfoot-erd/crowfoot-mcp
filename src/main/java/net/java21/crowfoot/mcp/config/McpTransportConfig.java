package net.java21.crowfoot.mcp.config;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import java.util.HashMap;
import java.util.Map;
import net.java21.crowfoot.mcp.auth.Caller;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStatelessServerTransport;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.ServerRequest;
import tools.jackson.databind.json.JsonMapper;

/**
 * MCP 전송 계층 — 요청 헤더를 도구가 읽을 수 있게 옮긴다 (10-mcp/00-mcp-server.md Section 2).
 * 자동 구성의 전송 빈은 헤더를 옮기지 않는다. 같은 구성에 헤더 추출만 더한 빈으로 바꾼다
 * (자동 구성은 이 타입의 빈이 있으면 만들지 않는다).
 */
@Configuration
public class McpTransportConfig {

    @Bean
    public WebMvcStatelessServerTransport webMvcStatelessServerTransport(
            JsonMapper jsonMapper, McpServerStreamableHttpProperties properties) {
        return WebMvcStatelessServerTransport.builder()
                .jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
                .messageEndpoint(properties.getMcpEndpoint())
                .contextExtractor(McpTransportConfig::callerHeaders)
                .build();
    }

    /** Gateway가 넣은 헤더 세 개만 옮긴다 — 그 밖의 헤더(Authorization 포함)는 도구에 넘기지 않는다 */
    private static McpTransportContext callerHeaders(ServerRequest request) {
        Map<String, Object> values = new HashMap<>();
        for (String name : new String[] {Caller.HEADER_USER_ID, Caller.HEADER_WORKSPACE_ID, Caller.HEADER_TOKEN_ID}) {
            String value = request.headers().firstHeader(name);
            if (value != null) {
                values.put(name, value);
            }
        }
        return McpTransportContext.create(values);
    }
}
