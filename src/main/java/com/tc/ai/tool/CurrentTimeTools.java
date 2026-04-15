package com.tc.ai.tool;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
public class CurrentTimeTools {

    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Tool(
            name = "getCurrentTime",
            description = "Get the current date and time for the application default timezone. Use this when the user asks for the current time, today's date, or other realtime time information."
    )
    public TimeResponse getCurrentTime() {
        ZonedDateTime now = ZonedDateTime.now(DEFAULT_ZONE);
        return new TimeResponse(
                now.format(DATE_TIME_FORMATTER),
                now.toLocalDate().toString(),
                DEFAULT_ZONE.getId(),
                now.getOffset().toString(),
                Instant.now().toString()
        );
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
