package com.tc.ai.api;

public record ChatStreamEvent(
        String eventType,
        String content,
        String toolName,
        String status,
        String metadata
) {
    public static ChatStreamEvent token(String content) {
        return new ChatStreamEvent("token", content, null, "streaming", null);
    }

    public static ChatStreamEvent done() {
        return new ChatStreamEvent("done", "", null, "completed", null);
    }

    public static ChatStreamEvent error(String content) {
        return new ChatStreamEvent("error", content, null, "failed", null);
    }
}
