package com.summercamp.project.schedule;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.summercamp.project.schedule.ReminderSubscriptionManager.Subscription;
import com.summercamp.project.weather.CurrentWeather;
import com.summercamp.project.weather.WeatherPeriod;
import com.summercamp.project.weather.WeatherReport;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScheduleTextTest {

    private WeatherReport report(String weather, String temperature) {
        return new WeatherReport(
                "北京",
                "2026-08-26 08:00",
                WeatherPeriod.CURRENT,
                new CurrentWeather(weather, temperature, "70%", "东南", "3"),
                List.of());
    }

    @Test
    void shouldSuggestIndoorWhenRainOrSnow() {
        assertTrue(WeatherAdvice.adviceFor(report("小雨", "20")).contains("室内"));
        assertTrue(WeatherAdvice.adviceFor(report("小雪", "-2")).contains("室内"));
    }

    @Test
    void shouldWarnAboutHeatAndCold() {
        assertTrue(WeatherAdvice.adviceFor(report("晴", "35")).contains("补水"));
        assertTrue(WeatherAdvice.adviceFor(report("多云", "5")).contains("热身"));
    }

    @Test
    void shouldGiveNormalAdviceOtherwise() {
        assertTrue(WeatherAdvice.adviceFor(report("晴", "24")).contains("适合运动"));
    }

    @Test
    void healthReminderShouldReferenceGoalAndCaloriesWhenPresent() {
        Subscription subscription = new Subscription("user-1", true, "减脂", 1797, "北京", null, null, 0);
        String message = HealthReminderText.build(subscription);

        assertTrue(message.contains("减脂"));
        assertTrue(message.contains("1797"));
        assertTrue(message.contains("7～9 小时睡眠"));
    }

    @Test
    void healthReminderShouldStayGenericWithoutProfile() {
        Subscription subscription = new Subscription("user-1", true, null, null, null, null, null, 0);
        String message = HealthReminderText.build(subscription);

        assertTrue(!message.contains("千卡"));
        assertTrue(message.contains("7～9 小时睡眠"));
    }
}
