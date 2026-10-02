package net.java21.crowfoot.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Crowfoot MCP 서버 — MCP 클라이언트(Claude 등)가 요구사항과 ERD를 읽고 쓰는 진입점 (10-mcp/00-mcp-server.md).
 * 저장소가 없다. 도구 호출을 받아 core의 API를 부른다.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class CrowfootMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(CrowfootMcpApplication.class, args);
    }
}
