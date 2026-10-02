package net.java21.crowfoot.mcp.core;

/**
 * core가 요청을 거절했거나 닿지 못했다 — 메시지는 MCP 클라이언트가 읽고 고칠 수 있게 쓴다
 * (10-mcp/00-mcp-server.md Section 10).
 */
public class CoreException extends RuntimeException {

    private final int status;
    private final String resultCode;

    public CoreException(int status, String resultCode, String message) {
        super(message);
        this.status = status;
        this.resultCode = resultCode;
    }

    public int status() {
        return status;
    }

    public String resultCode() {
        return resultCode;
    }
}
