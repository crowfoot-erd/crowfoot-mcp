package net.java21.crowfoot.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 서버 설정 (10-mcp/00-mcp-server.md Section 9).
 *
 * @param coreBaseUrl core의 기점 — 로컬 http://localhost:8082, 운영 http://crowfoot-core-api
 * @param webBaseUrl  문서 주소의 기점 — 로컬 http://localhost:8080, 운영 https://crowfoot.java21.net
 * @param databaseManagerBaseUrl DB 매니저의 기점(샘플 데이터 넣기) — 로컬 http://localhost:8084, 운영 http://crowfoot-database-manager
 */
@ConfigurationProperties("crowfoot.mcp")
public record McpProperties(String coreBaseUrl, String webBaseUrl, String databaseManagerBaseUrl) {
}
