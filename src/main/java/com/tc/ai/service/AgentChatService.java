package com.tc.ai.service;

import com.tc.ai.api.ChatStreamEvent;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

public interface AgentChatService {

    String complete(String message, MultipartFile file);

    Flux<ChatStreamEvent> stream(String message, MultipartFile file);
}
