package com.summercamp.project.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summercamp.project.schedule.ReminderSubscriptionManager.Subscription;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReminderSubscriptionManagerTest {

    @TempDir
    Path tempDir;

    private ReminderSubscriptionManager manager() {
        return new ReminderSubscriptionManager(new ObjectMapper(), tempDir.resolve("subs.json").toString());
    }

    @Test
    void shouldSubscribeHealthAndWeatherIndependently() {
        ReminderSubscriptionManager manager = manager();
        manager.subscribeHealth("user-1", "减脂", 1797);
        manager.subscribeWeather("user-1", "北京");

        Subscription subscription = manager.find("user-1").orElseThrow();
        assertTrue(subscription.healthReminder());
        assertEquals("减脂", subscription.goalChinese());
        assertEquals(1797, subscription.targetCalories());
        assertEquals("北京", subscription.city());
    }

    @Test
    void shouldListOnlyMatchingSubscribers() {
        ReminderSubscriptionManager manager = manager();
        manager.subscribeHealth("user-1", "减脂", 1797);
        manager.subscribeWeather("user-2", "上海");
        manager.subscribeHealth("user-3", "增肌", 2800);
        manager.subscribeWeather("user-3", "南昌");

        assertEquals(Set.of("user-1", "user-3"),
                manager.allHealthSubscribers().stream().map(Subscription::userId).collect(Collectors.toSet()));
        assertEquals(Set.of("user-2", "user-3"),
                manager.allWeatherSubscribers().stream().map(Subscription::userId).collect(Collectors.toSet()));
    }

    @Test
    void shouldUnsubscribeWithoutLosingTheOther() {
        ReminderSubscriptionManager manager = manager();
        manager.subscribeHealth("user-1", "减脂", 1797);
        manager.subscribeWeather("user-1", "北京");

        assertTrue(manager.unsubscribeHealth("user-1"));
        Subscription subscription = manager.find("user-1").orElseThrow();
        assertFalse(subscription.healthReminder());
        assertEquals("北京", subscription.city());
        assertTrue(manager.unsubscribeWeather("user-1"));
        assertEquals(Optional.empty(), manager.find("user-1"));
    }

    @Test
    void shouldPersistAndReloadSubscriptions() {
        String file = tempDir.resolve("subs.json").toString();
        ReminderSubscriptionManager first = new ReminderSubscriptionManager(new ObjectMapper(), file);
        first.subscribeHealth("user-1", "减脂", 1797, "22:00");
        first.subscribeWeather("user-1", "北京", "08:00");

        ReminderSubscriptionManager second = new ReminderSubscriptionManager(new ObjectMapper(), file);
        Subscription reloaded = second.find("user-1").orElseThrow();
        assertTrue(reloaded.healthReminder());
        assertEquals("减脂", reloaded.goalChinese());
        assertEquals(1797, reloaded.targetCalories());
        assertEquals("北京", reloaded.city());
        assertEquals("22:00", reloaded.healthReminderTime());
        assertEquals("08:00", reloaded.weatherDigestTime());
    }

    @Test
    void shouldUpdateTimesIndependently() {
        ReminderSubscriptionManager manager = manager();
        manager.subscribeHealth("user-1", "减脂", 1797);
        manager.subscribeWeather("user-1", "北京");

        manager.updateHealthReminderTime("user-1", "21:30");
        assertTrue(manager.updateWeatherDigestTime("user-1", "07:00"));

        Subscription updated = manager.find("user-1").orElseThrow();
        assertEquals("21:30", updated.healthReminderTime());
        assertEquals("07:00", updated.weatherDigestTime());
        assertEquals("减脂", updated.goalChinese());
        assertEquals("北京", updated.city());

        // 未订阅天气的用户改天气时间应失败
        assertFalse(manager.updateWeatherDigestTime("user-9", "07:00"));
    }

    @Test
    void shouldReturnSummary() {
        ReminderSubscriptionManager manager = manager();
        assertTrue(manager.summary("user-1").contains("还没有订阅"));
        manager.subscribeWeather("user-1", "北京", "08:00");
        manager.subscribeHealth("user-1", "减脂", 1797, "21:30");
        String summary = manager.summary("user-1");
        assertTrue(summary.contains("北京 天气播报（每天 08:00）"));
        assertTrue(summary.contains("健康提醒（每天 21:30）"));
    }

    @Test
    void shouldSubscribeAndUnsubscribeLunchMenuIndependently() {
        ReminderSubscriptionManager manager = manager();
        manager.subscribeWeather("user-1", "北京");
        manager.subscribeLunchMenu("user-1", "12:00");

        Subscription subscription = manager.find("user-1").orElseThrow();
        assertTrue(subscription.lunchMenu());
        assertEquals("12:00", subscription.lunchMenuTime());
        assertEquals("北京", subscription.city());

        assertTrue(manager.unsubscribeLunchMenu("user-1"));
        Subscription after = manager.find("user-1").orElseThrow();
        assertFalse(after.lunchMenu());
        assertEquals("北京", after.city());
        assertFalse(manager.unsubscribeLunchMenu("user-9"));
    }

    @Test
    void shouldListLunchMenuSubscribersOnly() {
        ReminderSubscriptionManager manager = manager();
        manager.subscribeLunchMenu("user-1");
        manager.subscribeWeather("user-2", "上海");
        manager.subscribeHealth("user-3", "减脂", 1797);

        assertEquals(Set.of("user-1"),
                manager.allLunchMenuSubscribers().stream().map(Subscription::userId).collect(Collectors.toSet()));
    }

    @Test
    void shouldPersistLunchMenuSubscription() {
        String file = tempDir.resolve("subs.json").toString();
        ReminderSubscriptionManager first = new ReminderSubscriptionManager(new ObjectMapper(), file);
        first.subscribeLunchMenu("user-1", "12:30");

        ReminderSubscriptionManager second = new ReminderSubscriptionManager(new ObjectMapper(), file);
        Subscription reloaded = second.find("user-1").orElseThrow();
        assertTrue(reloaded.lunchMenu());
        assertEquals("12:30", reloaded.lunchMenuTime());
    }

    @Test
    void shouldUpdateLunchMenuTimeOnlyWhenSubscribed() {
        ReminderSubscriptionManager manager = manager();
        manager.subscribeLunchMenu("user-1");

        assertTrue(manager.updateLunchMenuTime("user-1", "12:30"));
        assertEquals("12:30", manager.find("user-1").orElseThrow().lunchMenuTime());
        assertFalse(manager.updateLunchMenuTime("user-9", "12:00"));
    }
}
