package com.summercamp.project.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.summercamp.project.schedule.ReminderStore;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 自定义定时提醒工具：模型负责把"明天上午10点提醒我交作业"解析为绝对时间与内容，
 * 写入本地提醒表，由调度器到点推送。
 */
@Component
public class AddReminderTool implements BotTool {

    private static final Logger LOGGER = LoggerFactory.getLogger(AddReminderTool.class);
    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter MINUTE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter SECOND_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 允许 2 分钟容差，避免模型给出的时间略早于当前时刻被误拒。 */
    private static final long GRACE_MILLIS = 2 * 60 * 1000L;
    /** 提醒最远可设置到一年后。 */
    private static final long MAX_AHEAD_MILLIS = 366L * 24 * 60 * 60 * 1000;

    private final ReminderStore reminderStore;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ToolDefinition definition;

    @Autowired
    public AddReminderTool(ReminderStore reminderStore, ObjectMapper objectMapper) {
        this(reminderStore, objectMapper, Clock.systemUTC());
    }

    AddReminderTool(ReminderStore reminderStore, ObjectMapper objectMapper, Clock clock) {
        this.reminderStore = reminderStore;
        this.objectMapper = objectMapper;
        this.clock = clock;
        ObjectNode schema = objectMapper.createObjectNode().put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("atIso")
                .put("type", "string")
                .put("description", "触发时间（北京时间），格式 yyyy-MM-dd HH:mm，如 2026-08-27 10:00。"
                        + "先调用 get_current_datetime 获取当前时间，再推算目标时间")
                .put("minLength", 10)
                .put("maxLength", 19);
        properties.putObject("content")
                .put("type", "string")
                .put("description", "提醒内容")
                .put("minLength", 1)
                .put("maxLength", 100);
        ArrayNode repeatEnum = properties.putObject("repeat")
                .put("type", "string")
                .put("description", "once=单次（默认），daily=每天同一时间重复")
                .putArray("enum");
        repeatEnum.add("once");
        repeatEnum.add("daily");
        ArrayNode required = schema.putArray("required");
        required.add("atIso");
        required.add("content");
        schema.put("additionalProperties", false);
        definition = new ToolDefinition(
                "add_reminder",
                "为用户设置一个定时提醒，到点向用户推送提醒内容。"
                        + "用户说\"几点提醒我做什么\"\"明天/后天/晚上提醒我\"时使用，"
                        + "时间表达不明确时先追问确认。",
                schema);
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public ToolResult execute(JsonNode arguments, ToolContext context) {
        String atIso = arguments.path("atIso").asText("").strip();
        String content = arguments.path("content").asText("").strip();
        // 模型可能把枚举写成中文，统一归一：daily/每天/每日/每天重复 都视为每日重复
        boolean repeatDaily = isDailyRepeat(arguments.path("repeat").asText(""));

        long atEpochMillis = parseAt(atIso);
        long nowMillis = clock.millis();
        if (atEpochMillis < nowMillis - GRACE_MILLIS) {
            throw new ToolExecutionException("提醒时间已过去，请给出未来的时间");
        }
        if (atEpochMillis > nowMillis + MAX_AHEAD_MILLIS) {
            throw new ToolExecutionException("提醒时间最远可设置到一年后");
        }
        // 每日提醒应永远指向"下一次"该时刻：若模型把"每天16:54"算成今天已过的 16:54，
        // 自动推到明天，避免被调度器当成一次性提醒立即触发删除
        if (repeatDaily && atEpochMillis < nowMillis) {
            atEpochMillis = ReminderStore.nextDailyTrigger(atEpochMillis, nowMillis);
        }

        String id = reminderStore.add(context.userId(), atEpochMillis, content, repeatDaily);
        if (id == null) {
            throw new ToolExecutionException("该用户提醒数量已达上限，请先取消一些提醒");
        }
        String when = LocalDateTime.ofInstant(Instant.ofEpochMilli(atEpochMillis), CHINA_ZONE)
                .format(MINUTE_FORMAT);
        LOGGER.info("设置提醒 userId={} atIso={} at={} repeatDaily={} id={}",
                context.userId(), atIso, when, repeatDaily, id);
        return ToolResult.text("已设置提醒：" + content + "（" + when
                + (repeatDaily ? "，每天重复" : "") + "）。");
    }

    private static boolean isDailyRepeat(String repeat) {
        if (repeat == null || repeat.isBlank()) {
            return false;
        }
        String normalized = repeat.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{S}\\s]+", "");
        return switch (normalized) {
            case "daily", "每天", "每日", "每天重复", "每日重复", "每天一次" -> true;
            default -> false;
        };
    }

    private long parseAt(String atIso) {
        if (atIso.isBlank()) {
            throw new ToolExecutionException("缺少提醒时间 atIso");
        }
        LocalDateTime parsed;
        try {
            parsed = LocalDateTime.parse(atIso, MINUTE_FORMAT);
        } catch (DateTimeParseException first) {
            try {
                parsed = LocalDateTime.parse(atIso, SECOND_FORMAT);
            } catch (DateTimeParseException second) {
                throw new ToolExecutionException("提醒时间格式应为 yyyy-MM-dd HH:mm，例如 2026-08-27 10:00");
            }
        }
        try {
            return parsed.atZone(CHINA_ZONE).toInstant().toEpochMilli();
        } catch (DateTimeException exception) {
            throw new ToolExecutionException("提醒时间超出支持范围：" + atIso);
        }
    }
}
