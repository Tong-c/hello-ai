package com.tc.ai.api;

public record ChatStreamEvent(
        String eventType,
        String content,
        String toolName,
        String toolCallId,
        String status,
        String metadata
) {
    public static ChatStreamEvent token(String content) {
        return new ChatStreamEvent("token", content, null, null, "streaming", null);
    }

    public static ChatStreamEvent tool(String toolName, String toolCallId, String content, String status, String metadata) {
        return new ChatStreamEvent("tool", content, toolName, toolCallId, status, metadata);
    }

    public static ChatStreamEvent done() {
        return new ChatStreamEvent("done", "", null, null, "completed", null);
    }

    public static ChatStreamEvent error(String content) {
        return new ChatStreamEvent("error", content, null, null, "failed", null);
    }
}
