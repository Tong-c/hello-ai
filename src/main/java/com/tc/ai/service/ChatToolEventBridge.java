package com.tc.ai.service;

import com.tc.ai.api.ChatStreamEvent;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Sinks;

@Component
public class ChatToolEventBridge {

    private final Map<String, Sinks.Many<ChatStreamEvent>> sinksByRequestId = new ConcurrentHashMap<>();

    public void attach(String requestId, Sinks.Many<ChatStreamEvent> sink) {
        sinksByRequestId.put(requestId, sink);
    }

    public void clear(String requestId) {
        sinksByRequestId.remove(requestId);
    }

    public String beginToolCall(String requestId, String toolName, String content, String metadata) {
        String toolCallId = UUID.randomUUID().toString();
        publishToolEvent(requestId, toolName, toolCallId, content, "started", metadata);
        return toolCallId;
    }

    public void publishToolRunning(String requestId, String toolName, String toolCallId, String content, String metadata) {
        publishToolEvent(requestId, toolName, toolCallId, content, "running", metadata);
    }

    public void publishToolSucceeded(String requestId, String toolName, String toolCallId, String content, String metadata) {
        publishToolEvent(requestId, toolName, toolCallId, content, "succeeded", metadata);
    }

    public void publishToolFailed(String requestId, String toolName, String toolCallId, String content, String metadata) {
        publishToolEvent(requestId, toolName, toolCallId, content, "failed", metadata);
    }

    private void publishToolEvent(
            String requestId,
            String toolName,
            String toolCallId,
            String content,
            String status,
            String metadata
    ) {
        Sinks.Many<ChatStreamEvent> sink = sinksByRequestId.get(requestId);
        if (sink == null) {
            return;
        }
        sink.tryEmitNext(ChatStreamEvent.tool(toolName, toolCallId, content, status, metadata));
    }
}
