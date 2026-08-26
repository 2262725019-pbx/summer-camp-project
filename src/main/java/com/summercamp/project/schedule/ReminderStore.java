package com.summercamp.project.schedule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 自定义定时提醒表：由大模型解析"明天上午10点提醒我交作业"写入，
 * 调度器每分钟扫描到点触发。变更即写 JSON 文件，重启自动加载恢复。
 */
@Component
public class ReminderStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReminderStore.class);

    /** 单个用户最多同时保留的提醒数，防止工具被滥用刷爆内存。 */
    public static final int MAX_REMINDERS_PER_USER = 50;

    public record Reminder(
            String id,
            String userId,
            long atEpochMillis,
            String content,
            boolean repeatDaily,
            long createdAtMillis) {

        public Reminder {
            id = id == null ? "" : id;
            userId = userId == null ? "" : userId;
            content = content == null ? "" : content.strip();
        }
    }

    private final ObjectMapper objectMapper;
    private final Path filePath;
    private final Map<String, Reminder> reminders = new ConcurrentHashMap<>();

    public ReminderStore(
            ObjectMapper objectMapper,
            @Value("${schedule.reminder-file:runtime/reminders.json}") String filePath) {
        this.objectMapper = objectMapper;
        this.filePath = Path.of(filePath);
        load();
    }

    /** 新增一条提醒，返回 id；超出单用户上限返回 null。 */
    public String add(String userId, long atEpochMillis, String content, boolean repeatDaily) {
        if (userId == null || userId.isBlank() || content == null || content.isBlank()) {
            return null;
        }
        long count = reminders.values().stream()
                .filter(reminder -> userId.equals(reminder.userId()))
                .count();
        if (count >= MAX_REMINDERS_PER_USER) {
            return null;
        }
        String id = UUID.randomUUID().toString().substring(0, 8);
        reminders.put(id, new Reminder(
                id, userId, atEpochMillis, content, repeatDaily, System.currentTimeMillis()));
        save();
        return id;
    }

    /** 当前已到触发时间的提醒（按触发时间排序）。 */
    public List<Reminder> dueAt(long nowEpochMillis) {
        return reminders.values().stream()
                .filter(reminder -> reminder.atEpochMillis() <= nowEpochMillis)
                .sorted(Comparator.comparingLong(Reminder::atEpochMillis))
                .toList();
    }

    /** 触发后落库：一次性删除；每日提醒推到下一个触发时间。 */
    public void markTriggered(Reminder reminder, long nowEpochMillis) {
        if (reminder.repeatDaily()) {
            long next = nextDailyTrigger(reminder.atEpochMillis(), nowEpochMillis);
            reminders.put(reminder.id(), new Reminder(
                    reminder.id(), reminder.userId(), next, reminder.content(), true,
                    reminder.createdAtMillis()));
        } else {
            reminders.remove(reminder.id());
        }
        save();
    }

    public List<Reminder> list(String userId) {
        return reminders.values().stream()
                .filter(reminder -> userId.equals(reminder.userId()))
                .sorted(Comparator.comparingLong(Reminder::atEpochMillis))
                .toList();
    }

    public boolean cancel(String userId, String id) {
        Reminder reminder = reminders.get(id);
        if (reminder == null || !userId.equals(reminder.userId())) {
            return false;
        }
        reminders.remove(id);
        save();
        return true;
    }

    /** 每日提醒下一次触发时间：从原触发点以 24 小时递增直到晚于当前时刻。 */
    public static long nextDailyTrigger(long atEpochMillis, long nowEpochMillis) {
        long DAY_MILLIS = 24L * 60 * 60 * 1000;
        long next = atEpochMillis + DAY_MILLIS;
        while (next <= nowEpochMillis) {
            next += DAY_MILLIS;
        }
        return next;
    }

    // ---------------------------------------------------------------- 持久化

    private void load() {
        if (!Files.exists(filePath)) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(Files.readAllBytes(filePath));
            if (!root.isArray()) {
                LOGGER.warn("提醒文件格式异常，忽略：{}", filePath);
                return;
            }
            for (JsonNode node : root) {
                String id = node.path("id").asText();
                if (id.isBlank()) {
                    continue;
                }
                reminders.put(id, new Reminder(
                        id,
                        node.path("userId").asText(),
                        node.path("atEpochMillis").asLong(),
                        node.path("content").asText(),
                        node.path("repeatDaily").asBoolean(false),
                        node.path("createdAtMillis").asLong(System.currentTimeMillis())));
            }
        } catch (IOException exception) {
            LOGGER.warn("读取提醒文件失败：{}", exception.getMessage());
        }
    }

    private void save() {
        try {
            Path parent = filePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            ArrayNode array = objectMapper.createArrayNode();
            for (Reminder reminder : reminders.values()) {
                ObjectNode node = array.addObject();
                node.put("id", reminder.id());
                node.put("userId", reminder.userId());
                node.put("atEpochMillis", reminder.atEpochMillis());
                node.put("content", reminder.content());
                node.put("repeatDaily", reminder.repeatDaily());
                node.put("createdAtMillis", reminder.createdAtMillis());
            }
            Files.writeString(filePath, objectMapper.writeValueAsString(array));
        } catch (IOException exception) {
            LOGGER.warn("写入提醒文件失败：{}", exception.getMessage());
        }
    }
}
