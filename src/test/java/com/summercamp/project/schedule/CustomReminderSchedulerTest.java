package com.summercamp.project.schedule;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summercamp.project.schedule.ReminderStore.Reminder;
import com.summercamp.project.wechat.WechatGateway;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CustomReminderSchedulerTest {

    @TempDir
    Path tempDir;

    private ReminderStore store() {
        return new ReminderStore(new ObjectMapper(), tempDir.resolve("r.json").toString());
    }

    @Test
    void shouldPushDueRemindersAndMarkTriggered() throws Exception {
        ReminderStore store = store();
        store.add("user-1", System.currentTimeMillis() - 60_000, "交作业", false);
        WechatGateway gateway = mock(WechatGateway.class);

        new CustomReminderScheduler(store, gateway).pushDueReminders();

        verify(gateway).sendText(eq("user-1"), contains("交作业"));
        org.junit.jupiter.api.Assertions.assertTrue(store.list("user-1").isEmpty());
    }

    @Test
    void shouldKeepReminderWhenPushFails() throws Exception {
        ReminderStore store = store();
        store.add("user-1", System.currentTimeMillis() - 60_000, "交作业", false);
        WechatGateway gateway = mock(WechatGateway.class);
        doThrow(new java.io.IOException("网关未连接")).when(gateway).sendText(eq("user-1"), org.mockito.ArgumentMatchers.anyString());

        new CustomReminderScheduler(store, gateway).pushDueReminders();

        verify(gateway).sendText(eq("user-1"), org.mockito.ArgumentMatchers.anyString());
        org.junit.jupiter.api.Assertions.assertEquals(1, store.list("user-1").size());
    }

    @Test
    void shouldNotPushRemindersInTheFuture() throws Exception {
        ReminderStore store = store();
        store.add("user-1", System.currentTimeMillis() + 60_000, "未来的", false);
        WechatGateway gateway = mock(WechatGateway.class);

        new CustomReminderScheduler(store, gateway).pushDueReminders();

        verify(gateway, never()).sendText(eq("user-1"), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void dailyReminderIsPushedAndScheduledForNextDay() throws Exception {
        ReminderStore store = store();
        String id = store.add("user-1", System.currentTimeMillis() - 60_000, "喝水", true);
        WechatGateway gateway = mock(WechatGateway.class);

        new CustomReminderScheduler(store, gateway).pushDueReminders();

        verify(gateway).sendText(eq("user-1"), contains("喝水"));
        Reminder after = store.list("user-1").getFirst();
        org.junit.jupiter.api.Assertions.assertTrue(after.atEpochMillis() > System.currentTimeMillis());
    }
}
