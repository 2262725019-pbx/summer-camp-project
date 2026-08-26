package com.summercamp.project.schedule;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summercamp.project.weather.CurrentWeather;
import com.summercamp.project.weather.WeatherClient;
import com.summercamp.project.weather.WeatherException;
import com.summercamp.project.weather.WeatherPeriod;
import com.summercamp.project.weather.WeatherReport;
import com.summercamp.project.wechat.WechatGateway;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReminderSchedulersTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");

    @TempDir
    Path tempDir;

    private ReminderSubscriptionManager manager() {
        return new ReminderSubscriptionManager(new ObjectMapper(), tempDir.resolve("subs.json").toString());
    }

    private Clock at(String hhmm) {
        String[] parts = hhmm.split(":");
        var time = java.time.LocalTime.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        return Clock.fixed(java.time.ZonedDateTime.of(2026, 8, 26, time.getHour(), time.getMinute(), 0, 0, SH)
                .toInstant(), SH);
    }

    @Test
    void weatherDigestShouldPushOnlyMatchingSubscribersAndSkipFailures() throws Exception {
        ReminderSubscriptionManager manager = manager();
        manager.subscribeWeather("user-1", "北京");              // 默认 07:30
        manager.subscribeWeather("user-2", "上海", "08:00");     // 指定 08:00
        manager.subscribeHealth("user-3", "减脂", 1797);         // 只订阅健康，不应收到天气

        WeatherClient weather = mock(WeatherClient.class);
        when(weather.query("北京", WeatherPeriod.TODAY))
                .thenReturn(new WeatherReport(
                        "北京", "t", WeatherPeriod.TODAY,
                        new CurrentWeather("晴", "24", "60%", "东南", "3"), List.of()));
        when(weather.query("上海", WeatherPeriod.TODAY))
                .thenThrow(new WeatherException("查询失败"));

        WechatGateway gateway = mock(WechatGateway.class);
        new WeatherDigestScheduler(manager, weather, gateway, LocalTime.of(7, 30), at("07:30"))
                .pushDueWeather();

        verify(gateway).sendText(eq("user-1"), contains("北京"));
        // user-2 指定 08:00，当前 07:30 不应推送；user-3 未订阅天气
        verify(gateway, never()).sendText(eq("user-2"), anyString());
        verify(gateway, never()).sendText(eq("user-3"), anyString());
    }

    @Test
    void weatherDigestShouldHonorPerUserTime() throws Exception {
        ReminderSubscriptionManager manager = manager();
        manager.subscribeWeather("user-1", "北京", "08:00");
        WeatherClient weather = mock(WeatherClient.class);
        when(weather.query("北京", WeatherPeriod.TODAY))
                .thenReturn(new WeatherReport(
                        "北京", "t", WeatherPeriod.TODAY,
                        new CurrentWeather("晴", "24", "60%", "东南", "3"), List.of()));
        WechatGateway gateway = mock(WechatGateway.class);

        WeatherDigestScheduler scheduler =
                new WeatherDigestScheduler(manager, weather, gateway, LocalTime.of(7, 30), at("07:30"));
        scheduler.pushDueWeather();
        verify(gateway, never()).sendText(eq("user-1"), anyString());

        scheduler = new WeatherDigestScheduler(manager, weather, gateway, LocalTime.of(7, 30), at("08:00"));
        scheduler.pushDueWeather();
        verify(gateway).sendText(eq("user-1"), contains("北京"));
    }

    @Test
    void weatherDigestShouldPushOnlyOncePerDay() throws Exception {
        ReminderSubscriptionManager manager = manager();
        manager.subscribeWeather("user-1", "北京");
        WeatherClient weather = mock(WeatherClient.class);
        when(weather.query("北京", WeatherPeriod.TODAY))
                .thenReturn(new WeatherReport(
                        "北京", "t", WeatherPeriod.TODAY,
                        new CurrentWeather("晴", "24", "60%", "东南", "3"), List.of()));
        WechatGateway gateway = mock(WechatGateway.class);
        WeatherDigestScheduler scheduler =
                new WeatherDigestScheduler(manager, weather, gateway, LocalTime.of(7, 30), at("07:30"));

        scheduler.pushDueWeather();
        scheduler.pushDueWeather();

        verify(gateway, times(1)).sendText(eq("user-1"), anyString());
    }

    @Test
    void healthReminderShouldPushOnlyHealthSubscribersAtTheirTime() throws Exception {
        ReminderSubscriptionManager manager = manager();
        manager.subscribeHealth("user-1", "减脂", 1797);            // 默认 21:00
        manager.subscribeHealth("user-2", "增肌", 2800, "20:00");   // 指定 20:00
        manager.subscribeWeather("user-3", "上海");                  // 只订阅天气

        WechatGateway gateway = mock(WechatGateway.class);
        new HealthReminderScheduler(manager, gateway, LocalTime.of(21, 0), at("21:00"))
                .pushDueHealthReminders();

        verify(gateway).sendText(eq("user-1"), contains("1797"));
        verify(gateway, never()).sendText(eq("user-2"), anyString()); // 20:00 未到
        verify(gateway, never()).sendText(eq("user-3"), anyString());
    }

    @Test
    void healthReminderShouldKeepRetryingWhenPushFails() throws Exception {
        ReminderSubscriptionManager manager = manager();
        manager.subscribeHealth("user-1", "减脂", 1797);
        WechatGateway gateway = mock(WechatGateway.class);
        doThrow(new java.io.IOException("网关未连接"))
                .when(gateway).sendText(eq("user-1"), anyString());
        HealthReminderScheduler scheduler =
                new HealthReminderScheduler(manager, gateway, LocalTime.of(21, 0), at("21:00"));

        scheduler.pushDueHealthReminders();

        org.junit.jupiter.api.Assertions.assertEquals(1, manager.allHealthSubscribers().size());
    }
}
