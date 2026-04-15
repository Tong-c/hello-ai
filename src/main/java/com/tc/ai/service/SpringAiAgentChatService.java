package com.tc.ai.service;

import com.tc.ai.api.ChatStreamEvent;
import com.tc.ai.tool.CalculatorTools;
import com.tc.ai.tool.CurrentTimeTools;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

@Service
public class SpringAiAgentChatService implements AgentChatService {

    private static final Set<String> TEXTUAL_CONTENT_TYPES = Set.of(
            MediaTypes.APPLICATION_JSON,
            MediaTypes.APPLICATION_XML,
            MediaTypes.TEXT_MARKDOWN,
            MediaTypes.TEXT_PLAIN,
            MediaTypes.TEXT_XML
    );

    private static final int MAX_FILE_CONTENT_CHARS = 8_000;
    private static final String SYSTEM_PROMPT = """
            You are a concise AI assistant for a Java demo application.
            Use the available tools whenever the user asks for current time, today's date, or other realtime time information.
            Use the calculator tool whenever the user asks for arithmetic or numeric calculations.
            Do not guess realtime values when a tool can provide them.
            """;

    private final ChatClient chatClient;
    private final CurrentTimeTools currentTimeTools;
    private final CalculatorTools calculatorTools;

    public SpringAiAgentChatService(
            ChatClient.Builder chatClientBuilder,
            CurrentTimeTools currentTimeTools,
            CalculatorTools calculatorTools
    ) {
        this.chatClient = chatClientBuilder.build();
        this.currentTimeTools = currentTimeTools;
        this.calculatorTools = calculatorTools;
    }

    @Override
    public String complete(String message, MultipartFile file) {
        String prompt = buildPrompt(message, file);
        return chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(prompt)
                .tools(currentTimeTools, calculatorTools)
                .call()
                .content();
    }

    @Override
    public Flux<ChatStreamEvent> stream(String message, MultipartFile file) {
        String prompt;

        try {
            prompt = buildPrompt(message, file);
        } catch (IllegalArgumentException ex) {
            return Flux.just(ChatStreamEvent.error(ex.getMessage()));
        }

        return chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(prompt)
                .tools(currentTimeTools, calculatorTools)
                .stream()
                .content()
                .map(ChatStreamEvent::token)
                .concatWithValues(ChatStreamEvent.done())
                .onErrorResume(ex -> Flux.just(ChatStreamEvent.error("Agent response failed: " + ex.getMessage())));
    }

    private String buildPrompt(String message, MultipartFile file) {
        String normalizedMessage = message == null ? "" : message.trim();
        if (!StringUtils.hasText(normalizedMessage) && (file == null || file.isEmpty())) {
            throw new IllegalArgumentException("message or file is required");
        }

        StringBuilder prompt = new StringBuilder();
        if (StringUtils.hasText(normalizedMessage)) {
            prompt.append(normalizedMessage);
        } else {
            prompt.append("Please analyze the uploaded file.");
        }

        if (file == null || file.isEmpty()) {
            return prompt.toString();
        }

        prompt.append("\n\nUploaded file context:\n");
        prompt.append("name: ").append(file.getOriginalFilename()).append('\n');
        prompt.append("contentType: ").append(file.getContentType()).append('\n');
        prompt.append("sizeBytes: ").append(file.getSize()).append('\n');

        if (isTextual(file)) {
            prompt.append("content:\n").append(readFileText(file));
        } else {
            prompt.append("content: [binary file omitted from prompt]");
        }

        return prompt.toString();
    }

    private boolean isTextual(MultipartFile file) {
        String contentType = file.getContentType();
        if (!StringUtils.hasText(contentType)) {
            return false;
        }
        return contentType.startsWith("text/") || TEXTUAL_CONTENT_TYPES.contains(contentType);
    }

    private String readFileText(MultipartFile file) {
        try {
            String content = new String(file.getBytes(), StandardCharsets.UTF_8);
            if (content.length() <= MAX_FILE_CONTENT_CHARS) {
                return content;
            }
            return content.substring(0, MAX_FILE_CONTENT_CHARS) + "\n[truncated]";
        } catch (IOException ex) {
            throw new IllegalArgumentException("uploaded file could not be read", ex);
        }
    }

    private static final class MediaTypes {
        private static final String APPLICATION_JSON = "application/json";
        private static final String APPLICATION_XML = "application/xml";
        private static final String TEXT_MARKDOWN = "text/markdown";
        private static final String TEXT_PLAIN = "text/plain";
        private static final String TEXT_XML = "text/xml";

        private MediaTypes() {
        }
    }
}
