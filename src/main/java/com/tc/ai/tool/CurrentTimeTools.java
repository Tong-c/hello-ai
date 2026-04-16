package com.tc.ai.tool;

import com.tc.ai.service.ChatToolEventBridge;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
public class CurrentTimeTools {

    private static final Logger log = LoggerFactory.getLogger(CurrentTimeTools.class);

    private static final long STATUS_PREVIEW_DELAY_MS = 10_000;
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final ChatToolEventBridge chatToolEventBridge;

    public CurrentTimeTools(ChatToolEventBridge chatToolEventBridge) {
        this.chatToolEventBridge = chatToolEventBridge;
    }

    @Tool(
            name = "getCurrentTime",
            description = "获取应用默认时区的当前日期和时间。当用户询问现在几点、今天几号、当前日期或其他实时日期时间信息时，应优先调用这个工具。"
    )
    public TimeResponse getCurrentTime(ToolContext toolContext) {
        String requestId = getRequiredRequestId(toolContext);
        String metadata = "timezone=" + DEFAULT_ZONE.getId();
        String toolCallId = chatToolEventBridge.beginToolCall(
                requestId,
                "getCurrentTime",
                "已开始调用时间工具。",
                metadata
        );
        pauseForStatusPreview();
        chatToolEventBridge.publishToolRunning(
                requestId,
                "getCurrentTime",
                toolCallId,
                "正在获取当前时间。",
                metadata
        );
        pauseForStatusPreview();

        try {
            ZonedDateTime now = ZonedDateTime.now(DEFAULT_ZONE);
            TimeResponse response = new TimeResponse(
                    now.format(DATE_TIME_FORMATTER),
                    now.toLocalDate().toString(),
                    DEFAULT_ZONE.getId(),
                    now.getOffset().toString(),
                    Instant.now().toString()
            );
            log.info("Tool getCurrentTime invoked: timezone={}, localDateTime={}",
                    response.timezone(),
                    response.localDateTime());
            chatToolEventBridge.publishToolSucceeded(
                    requestId,
                    "getCurrentTime",
                    toolCallId,
                    "已返回当前时间。",
                    "time=" + response.localDateTime() + ", timezone=" + response.timezone()
            );
            return response;
        } catch (RuntimeException ex) {
            chatToolEventBridge.publishToolFailed(
                    requestId,
                    "getCurrentTime",
                    toolCallId,
                    shortErrorMessage(ex),
                    metadata
            );
            throw ex;
        }
    }

    private String shortErrorMessage(Throwable ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        String normalized = message.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 120) + "...";
    }

    private void pauseForStatusPreview() {
        try {
            Thread.sleep(STATUS_PREVIEW_DELAY_MS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("tool status preview interrupted", ex);
        }
    }

    private String getRequiredRequestId(ToolContext toolContext) {
        Map<String, Object> context = toolContext == null ? Map.of() : toolContext.getContext();
        Object requestId = context.get("chatRequestId");
        if (requestId instanceof String value && !value.isBlank()) {
            return value;
        }
        throw new IllegalStateException("chatRequestId is missing from tool context");
    }

    public record TimeResponse(
            String localDateTime,
            String localDate,
            String timezone,
            String utcOffset,
            String instant
    ) {
    }
}
