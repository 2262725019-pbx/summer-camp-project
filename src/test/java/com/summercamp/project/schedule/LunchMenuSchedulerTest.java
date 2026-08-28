package com.summercamp.project.schedule;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summercamp.project.skill.nutrition.FoodCatalog;
import com.summercamp.project.wechat.WechatGateway;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LunchMenuSchedulerTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");

    @TempDir
    Path tempDir;

    private ReminderSubscriptionManager manager() {
        return new ReminderSubscriptionManager(new ObjectMapper(), tempDir.resolve("subs.json").toString());
    }

    private FoodCatalog foods() {
        return new FoodCatalog(new ObjectMapper());
    }

    private Clock at(String hhmm) {
        String[] parts = hhmm.split(":");
        var time = LocalTime.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        return Clock.fixed(ZonedDateTime.of(2026, 8, 26, time.getHour(), time.getMinute(), 0, 0, SH)
                .toInstant(), SH);
    }

    @Test
    void shouldPushMenuAtSubscribedTime() throws Exception {
        ReminderSubscriptionManager manager = manager();
        manager.subscribeLunchMenu("user-1", "12:00");
        WechatGateway gateway = mock(WechatGateway.class);

        new LunchMenuScheduler(manager, foods(), gateway, LocalTime.of(12, 0), at("12:00"))
                .pushDueLunchMenus();

        verify(gateway).sendText(eq("user-1"), contains("今日午餐菜单"));
    }

    @Test
    void shouldNotPushAtOtherTime() throws Exception {
        ReminderSubscriptionManager manager = manager();
        manager.subscribeLunchMenu("user-1", "12:00");
        WechatGateway gateway = mock(WechatGateway.class);

        new LunchMenuScheduler(manager, foods(), gateway, LocalTime.of(12, 0), at("11:00"))
                .pushDueLunchMenus();

        verify(gateway, never()).sendText(eq("user-1"), anyString());
    }

    @Test
    void shouldUseDefaultTimeWhenSubscriberDidNotSpecify() throws Exception {
        ReminderSubscriptionManager manager = manager();
        manager.subscribeLunchMenu("user-1", null);
        WechatGateway gateway = mock(WechatGateway.class);

        new LunchMenuScheduler(manager, foods(), gateway, LocalTime.of(12, 0), at("12:00"))
                .pushDueLunchMenus();

        verify(gateway).sendText(eq("user-1"), contains("今日午餐菜单"));
    }

    @Test
    void shouldPushOnlyOncePerDay() throws Exception {
        ReminderSubscriptionManager manager = manager();
        manager.subscribeLunchMenu("user-1", "12:00");
        WechatGateway gateway = mock(WechatGateway.class);
        LunchMenuScheduler scheduler =
                new LunchMenuScheduler(manager, foods(), gateway, LocalTime.of(12, 0), at("12:00"));

        scheduler.pushDueLunchMenus();
        scheduler.pushDueLunchMenus();

        verify(gateway, times(1)).sendText(eq("user-1"), anyString());
    }

    @Test
    void shouldNotPushToNonSubscribers() throws Exception {
        ReminderSubscriptionManager manager = manager();
        manager.subscribeWeather("user-1", "北京");
        WechatGateway gateway = mock(WechatGateway.class);

        new LunchMenuScheduler(manager, foods(), gateway, LocalTime.of(12, 0), at("12:00"))
                .pushDueLunchMenus();

        verify(gateway, never()).sendText(eq("user-1"), anyString());
    }
}
