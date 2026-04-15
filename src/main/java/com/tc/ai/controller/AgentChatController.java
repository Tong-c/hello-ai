package com.tc.ai.controller;

import com.tc.ai.api.ChatStreamEvent;
import com.tc.ai.service.AgentChatService;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api")
public class AgentChatController {

    private final AgentChatService agentChatService;

    public AgentChatController(AgentChatService agentChatService) {
        this.agentChatService = agentChatService;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @PostMapping(path = "/chat", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ChatResponse chat(@RequestParam("message") String message, @RequestPart(value = "file", required = false) MultipartFile file) {
        return new ChatResponse(agentChatService.complete(message, file));
    }

    @PostMapping(path = "/chat/stream", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ChatStreamEvent>> stream(@RequestParam("message") String message, @RequestPart(value = "file", required = false) MultipartFile file) {
        return agentChatService.stream(message, file)
                .map(event -> ServerSentEvent.<ChatStreamEvent>builder()
                        .event(event.eventType())
                        .data(event)
                        .build());
    }

    public record ChatResponse(String content) {
    }
}
