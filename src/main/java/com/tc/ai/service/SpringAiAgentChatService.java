package com.tc.ai.service;

import com.tc.ai.api.ChatStreamEvent;
import com.tc.ai.tool.CalculatorTools;
import com.tc.ai.tool.CurrentTimeTools;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

@Service
public class SpringAiAgentChatService implements AgentChatService {

    private static final Logger log = LoggerFactory.getLogger(SpringAiAgentChatService.class);

    private static final Set<String> TEXTUAL_CONTENT_TYPES = Set.of(
            MediaTypes.APPLICATION_JSON,
            MediaTypes.APPLICATION_XML,
            MediaTypes.TEXT_MARKDOWN,
            MediaTypes.TEXT_PLAIN,
            MediaTypes.TEXT_XML
    );

    private static final int MAX_FILE_CONTENT_CHARS = 8_000;
    private static final String SYSTEM_PROMPT = """
            你是一个用于 Java 示例项目的简洁 AI 助手。
            当用户询问当前时间、今天日期或其他实时日期时间信息时，优先调用可用工具，不要直接猜测。
            当用户询问算术计算、数值运算或乘方结果时，优先调用计算器工具，不要直接心算。
            如果工具可以提供更可靠的结果，就不要跳过工具直接回答。
            """;

    private final ChatClient chatClient;
    private final CurrentTimeTools currentTimeTools;
    private final CalculatorTools calculatorTools;
    private final ChatToolEventBridge chatToolEventBridge;

    public SpringAiAgentChatService(
            ChatClient.Builder chatClientBuilder,
            CurrentTimeTools currentTimeTools,
            CalculatorTools calculatorTools,
            ChatToolEventBridge chatToolEventBridge
    ) {
        this.chatClient = chatClientBuilder.build();
        this.currentTimeTools = currentTimeTools;
        this.calculatorTools = calculatorTools;
        this.chatToolEventBridge = chatToolEventBridge;
    }

    @Override
    public String complete(String message, MultipartFile file) {
        String prompt = buildPrompt(message, file);
        log.info("Calling chat model for sync response: promptLength={}, hasFile={}, preview={}",
                prompt.length(),
                file != null && !file.isEmpty(),
                preview(prompt));

        String content = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(prompt)
                .tools(currentTimeTools, calculatorTools)
                .call()
                .content();

        log.info("Chat model sync response received: contentLength={}, preview={}",
                content == null ? 0 : content.length(),
                preview(content));
        return content;
    }

    @Override
    public Flux<ChatStreamEvent> stream(String message, MultipartFile file) {
        String prompt;

        try {
            prompt = buildPrompt(message, file);
        } catch (IllegalArgumentException ex) {
            log.warn("Rejected stream request: {}", ex.getMessage());
            return Flux.just(ChatStreamEvent.error(ex.getMessage()));
        }

        log.info("Calling chat model for stream response: promptLength={}, hasFile={}, preview={}",
                prompt.length(),
                file != null && !file.isEmpty(),
                preview(prompt));

        String requestId = UUID.randomUUID().toString();
        Sinks.Many<ChatStreamEvent> toolEventSink = Sinks.many().multicast().onBackpressureBuffer();

        Flux<ChatStreamEvent> modelFlux = Flux.defer(() -> {
                    chatToolEventBridge.attach(requestId, toolEventSink);
                    return chatClient.prompt()
                            .system(SYSTEM_PROMPT)
                            .user(prompt)
                            .toolContext(Map.of("chatRequestId", requestId))
                            .tools(currentTimeTools, calculatorTools)
                            .stream()
                            .content()
                            .map(ChatStreamEvent::token);
                })
                .concatWithValues(ChatStreamEvent.done())
                .doOnSubscribe(subscription -> log.debug("Chat stream subscribed"))
                .doOnComplete(() -> log.info("Chat stream completed"))
                .doOnError(ex -> log.error("Chat stream failed: {}", ex.getMessage(), ex))
                .doFinally(signalType -> {
                    chatToolEventBridge.clear(requestId);
                    toolEventSink.tryEmitComplete();
                })
                .onErrorResume(ex -> Flux.just(ChatStreamEvent.error("Agent response failed: " + ex.getMessage())));

        return Flux.merge(toolEventSink.asFlux(), modelFlux);
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
            prompt.append("请分析用户上传的文件。");
        }

        if (file == null || file.isEmpty()) {
            log.debug("Built prompt without file: length={}", prompt.length());
            return prompt.toString();
        }

        prompt.append("\n\n上传文件上下文：\n");
        prompt.append("文件名: ").append(file.getOriginalFilename()).append('\n');
        prompt.append("内容类型: ").append(file.getContentType()).append('\n');
        prompt.append("字节大小: ").append(file.getSize()).append('\n');

        if (isTextual(file)) {
            prompt.append("文件内容:\n").append(readFileText(file));
        } else {
            prompt.append("文件内容: [二进制文件内容已省略]");
        }

        log.debug("Built prompt with file: name={}, contentType={}, sizeBytes={}, promptLength={}",
                file.getOriginalFilename(),
                file.getContentType(),
                file.getSize(),
                prompt.length());
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

    private String preview(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 120) + "...";
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
