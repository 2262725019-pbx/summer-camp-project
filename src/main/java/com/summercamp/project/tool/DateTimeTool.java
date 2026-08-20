package com.summercamp.project.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 合并 ClockTool 与 DateTimeTool 后保留的统一日期时间工具。 */
@Component
public class DateTimeTool implements BotTool {

    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.CHINA);
    private static final DateTimeFormatter WEEKDAY =
            DateTimeFormatter.ofPattern("EEEE", Locale.CHINA);

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ToolDefinition definition;

    @Autowired
    public DateTimeTool(ObjectMapper objectMapper) {
        this(objectMapper, Clock.systemUTC());
    }

    DateTimeTool(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
        ObjectNode schema = objectMapper.createObjectNode().put("type", "object");
        schema.putObject("properties").putObject("timezone")
                .put("type", "string")
                .put("description", "IANA 时区，例如 Asia/Shanghai、UTC；默认 Asia/Shanghai")
                .put("maxLength", 64);
        schema.put("additionalProperties", false);
        definition = new ToolDefinition(
                "get_current_datetime",
                "获取指定时区的当前日期、时间和星期。用户问现在几点、今天几号或星期几时使用。",
                schema);
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public ToolResult execute(JsonNode arguments, ToolContext context) {
        String requestedZone = arguments.path("timezone").asText("").strip();
        ZoneId zone;
        try {
            zone = requestedZone.isBlank() ? DEFAULT_ZONE : ZoneId.of(requestedZone);
        } catch (DateTimeException exception) {
            throw new ToolExecutionException("不支持的时区：" + requestedZone);
        }
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(zone));
        ObjectNode result = objectMapper.createObjectNode();
        result.put("timezone", zone.getId());
        result.put("date", now.toLocalDate().toString());
        result.put("time", now.toLocalTime().withNano(0).toString());
        result.put("weekday", WEEKDAY.format(now));
        result.put("formatted", DATE_TIME.format(now) + " " + WEEKDAY.format(now));
        return ToolResult.data(result);
    }
}
