package com.exai.web.tool;

/** Immutable caller context shared by internal tool handlers. */
public final class ToolContext {
    private final String cwd;
    private final String sessionId;
    private final long turnId;

    public ToolContext(String cwd, String sessionId, long turnId) {
        this.cwd = cwd == null ? "" : cwd;
        this.sessionId = sessionId == null ? "" : sessionId;
        this.turnId = turnId;
    }

    public static ToolContext anonymous() {
        return new ToolContext("", "", 0L);
    }

    public String getCwd() { return cwd; }
    public String getSessionId() { return sessionId; }
    public long getTurnId() { return turnId; }
}
