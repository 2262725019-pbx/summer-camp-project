package com.summercamp.project.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.summercamp.project.schedule.ReminderStore;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AddReminderToolTest {

    /** 2026-08-26 10:00 北京时间 = 2026-08-26 02:00 UTC。 */
    private static final Instant FIXED_NOW = Instant.parse("2026-08-26T02:00:00Z");

    @TempDir
    Path tempDir;

    private AddReminderTool tool(ReminderStore store) {
        return new AddReminderTool(store, new ObjectMapper(), Clock.fixed(FIXED_NOW, ZoneId.of("UTC")));
    }

    private ObjectNode arguments(String atIso, String content, String repeat) {
        ObjectNode node = new ObjectMapper().createObjectNode();
        node.put("atIso", atIso);
        node.put("content", content);
        if (repeat != null) {
            node.put("repeat", repeat);
        }
        return node;
    }

    @Test
    void shouldAddOneOffReminder() {
        ReminderStore store = new ReminderStore(new ObjectMapper(), tempDir.resolve("r.json").toString());
        ToolResult result = tool(store).execute(
                arguments("2026-08-27 10:00", "交作业", null),
                new ToolContext("user-1", "明天上午10点提醒我交作业", java.util.List.of()));

        assertEquals(1, store.list("user-1").size());
        String reply = ((ToolResult.Text) result).content();
        assertTrue(reply.contains("2026-08-27 10:00"));
        assertTrue(reply.contains("交作业"));
    }

    @Test
    void shouldSupportDailyRepeat() {
        ReminderStore store = new ReminderStore(new ObjectMapper(), tempDir.resolve("r.json").toString());
        tool(store).execute(arguments("2026-08-26 15:00", "喝水", "daily"), toolContext());

        assertTrue(store.list("user-1").getFirst().repeatDaily());
    }

    @Test
    void shouldTreatChineseRepeatAsDaily() {
        ReminderStore store = new ReminderStore(new ObjectMapper(), tempDir.resolve("r.json").toString());
        tool(store).execute(arguments("2026-08-26 15:00", "喝水", "每天"), toolContext());

        assertTrue(store.list("user-1").getFirst().repeatDaily());
    }

    @Test
    void dailyReminderWithSlightlyPastTimeMovesToNextDay() {
        ReminderStore store = new ReminderStore(new ObjectMapper(), tempDir.resolve("r.json").toString());
        // atIso=09:59 北京时间 = 01:59 UTC，比固定时钟 02:00 UTC 早 1 分钟（容差内），
        // 每日提醒应自动推到下一次 09:59，而不是立即触发删除
        tool(store).execute(arguments("2026-08-26 09:59", "喝水", "daily"), toolContext());

        ReminderStore.Reminder reminder = store.list("user-1").getFirst();
        assertTrue(reminder.atEpochMillis() > FIXED_NOW.toEpochMilli());
        assertTrue(reminder.repeatDaily());
    }

    @Test
    void shouldRejectPastTime() {
        ReminderStore store = new ReminderStore(new ObjectMapper(), tempDir.resolve("r.json").toString());
        assertThrows(ToolExecutionException.class,
                () -> tool(store).execute(arguments("2026-08-25 10:00", "交作业", null), toolContext()));
        assertTrue(store.list("user-1").isEmpty());
    }

    @Test
    void shouldRejectBeyondOneYear() {
        ReminderStore store = new ReminderStore(new ObjectMapper(), tempDir.resolve("r.json").toString());
        assertThrows(ToolExecutionException.class,
                () -> tool(store).execute(arguments("2028-01-01 10:00", "交作业", null), toolContext()));
    }

    @Test
    void shouldRejectMalformedTime() {
        ReminderStore store = new ReminderStore(new ObjectMapper(), tempDir.resolve("r.json").toString());
        assertThrows(ToolExecutionException.class,
                () -> tool(store).execute(arguments("10点", "交作业", null), toolContext()));
    }

    private ToolContext toolContext() {
        return new ToolContext("user-1", "提醒我交作业", java.util.List.of());
    }
}
