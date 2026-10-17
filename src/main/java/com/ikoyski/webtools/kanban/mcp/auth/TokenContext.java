package com.ikoyski.webtools.kanban.mcp.auth;

/**
 * Holds the caller's Authorization header value for the duration of one request.
 * The MCP app has no credentials of its own: every outbound call to the Kanban API
 * reuses the token the agent sent in.
 */
public final class TokenContext {

    private static final ThreadLocal<String> AUTH_HEADER = new ThreadLocal<>();

    private TokenContext() {}

    public static void set(String authorizationHeader) {
        AUTH_HEADER.set(authorizationHeader);
    }

    public static String get() {
        return AUTH_HEADER.get();
    }

    public static void clear() {
        AUTH_HEADER.remove();
    }
}
