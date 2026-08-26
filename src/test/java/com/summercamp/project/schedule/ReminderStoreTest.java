package com.summercamp.project.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summercamp.project.schedule.ReminderStore.Reminder;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReminderStoreTest {

    private static final long NOW = 1_752_000_000_000L; // 固定基准毫秒

    @TempDir
    Path tempDir;

    private ReminderStore store() {
        return new ReminderStore(new ObjectMapper(), tempDir.resolve("reminders.json").toString());
    }

    @Test
    void shouldAddAndListRemindersForUser() {
        ReminderStore store = store();
        String id = store.add("user-1", NOW + 60_000, "交作业", false);

        List<Reminder> reminders = store.list("user-1");
        assertEquals(1, reminders.size());
        assertEquals(id, reminders.getFirst().id());
        assertEquals("交作业", reminders.getFirst().content());
        assertTrue(store.list("user-2").isEmpty());
    }

    @Test
    void shouldReturnOnlyDueReminders() {
        ReminderStore store = store();
        store.add("user-1", NOW - 60_000, "已到期", false);
        store.add("user-1", NOW + 60_000, "未到期", false);

        List<Reminder> due = store.dueAt(NOW);
        assertEquals(1, due.size());
        assertEquals("已到期", due.getFirst().content());
    }

    @Test
    void onceReminderRemovedAfterTriggerAndDailyMovesToNextDay() {
        ReminderStore store = store();
        String onceId = store.add("user-1", NOW - 60_000, "单次提醒", false);
        String dailyId = store.add("user-1", NOW - 60_000, "每日提醒", true);

        for (Reminder reminder : store.dueAt(NOW)) {
            store.markTriggered(reminder, NOW);
        }

        assertFalse(store.list("user-1").stream().anyMatch(r -> r.id().equals(onceId)));
        Reminder daily = store.list("user-1").stream()
                .filter(r -> r.id().equals(dailyId))
                .findFirst()
                .orElseThrow();
        assertTrue(daily.atEpochMillis() > NOW);
        assertEquals("每日提醒", daily.content());
    }

    @Test
    void shouldCancelOnlyOwnReminder() {
        ReminderStore store = store();
        String id = store.add("user-1", NOW + 60_000, "交作业", false);
        store.add("user-2", NOW + 60_000, "别人的", false);

        assertFalse(store.cancel("user-2", id));
        assertTrue(store.cancel("user-1", id));
        assertTrue(store.list("user-1").isEmpty());
    }

    @Test
    void shouldEnforcePerUserLimit() {
        ReminderStore store = store();
        String id = null;
        for (int index = 0; index < ReminderStore.MAX_REMINDERS_PER_USER; index++) {
            id = store.add("user-1", NOW + 60_000L * (index + 1), "提醒" + index, false);
        }
        assertNull(store.add("user-1", NOW + 3_600_000, "超限", false));
        assertTrue(id != null);
        // 其他用户不受影响
        assertTrue(store.add("user-2", NOW + 60_000, "ok", false) != null);
    }

    @Test
    void shouldPersistAndReload() {
        String file = tempDir.resolve("reminders.json").toString();
        ReminderStore first = new ReminderStore(new ObjectMapper(), file);
        first.add("user-1", NOW + 60_000, "交作业", true);

        ReminderStore second = new ReminderStore(new ObjectMapper(), file);
        List<Reminder> reloaded = second.list("user-1");
        assertEquals(1, reloaded.size());
        assertEquals("交作业", reloaded.getFirst().content());
        assertTrue(reloaded.getFirst().repeatDaily());
    }
}
