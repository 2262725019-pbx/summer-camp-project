package com.summercamp.project.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summercamp.project.skill.SkillContext;
import com.summercamp.project.skill.SkillResult;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReminderSubscriptionSkillTest {

    @TempDir
    Path tempDir;

    private ReminderSubscriptionSkill skill() {
        return new ReminderSubscriptionSkill(
                new ReminderSubscriptionManager(new ObjectMapper(), tempDir.resolve("subs.json").toString()));
    }

    @Test
    void shouldMatchSubscriptionCommandsOnly() {
        ReminderSubscriptionSkill skill = skill();
        assertTrue(skill.matchScore("订阅天气 北京") > 0);
        assertTrue(skill.matchScore("订阅健康提醒") > 0);
        assertTrue(skill.matchScore("退订提醒") > 0);
        assertTrue(skill.matchScore("我的订阅") > 0);
        assertTrue(skill.matchScore("订阅午餐菜单") > 0);
        assertTrue(skill.matchScore("取消午餐菜单") > 0);
        assertTrue(skill.matchScore("午餐菜单改到12点") > 0);
        assertEquals(0, skill.matchScore("今天天气怎么样"));
        assertEquals(0, skill.matchScore("帮我制定健康生活规划"));
    }

    @Test
    void shouldSubscribeWeatherWithCity() {
        SkillResult result = skill().execute(context("订阅天气 北京"));

        assertEquals(SkillResult.Status.COMPLETED, result.status());
        assertTrue(result.reply().contains("订阅成功"));
        assertTrue(result.reply().contains("北京 天气播报"));
    }

    @Test
    void shouldParseCustomTimeOnSubscribe() {
        ReminderSubscriptionManager manager =
                new ReminderSubscriptionManager(new ObjectMapper(), tempDir.resolve("subs.json").toString());
        ReminderSubscriptionSkill skill = new ReminderSubscriptionSkill(manager);

        SkillResult result = skill.execute(context("订阅天气 北京 8点"));
        assertTrue(result.reply().contains("每天 08:00"));
        assertEquals("08:00", manager.find("user-a").orElseThrow().weatherDigestTime());

        skill.execute(context("订阅健康提醒 晚上9点半"));
        assertEquals("21:30", manager.find("user-a").orElseThrow().healthReminderTime());
    }

    @Test
    void shouldChangeHealthReminderTime() {
        ReminderSubscriptionManager manager =
                new ReminderSubscriptionManager(new ObjectMapper(), tempDir.resolve("subs.json").toString());
        manager.subscribeHealth("user-a", "减脂", 1797);
        ReminderSubscriptionSkill skill = new ReminderSubscriptionSkill(manager);

        SkillResult result = skill.execute(context("健康提醒改到21:30"));

        assertTrue(result.reply().contains("21:30"));
        assertEquals("21:30", manager.find("user-a").orElseThrow().healthReminderTime());
    }

    @Test
    void shouldChangeWeatherTimeOnlyWhenSubscribed() {
        ReminderSubscriptionManager manager =
                new ReminderSubscriptionManager(new ObjectMapper(), tempDir.resolve("subs.json").toString());
        ReminderSubscriptionSkill skill = new ReminderSubscriptionSkill(manager);

        SkillResult noSubscription = skill.execute(context("天气播报改到8点"));
        assertTrue(noSubscription.reply().contains("还没有订阅天气播报"));

        manager.subscribeWeather("user-a", "北京");
        SkillResult changed = skill.execute(context("天气播报改到8点"));
        assertTrue(changed.reply().contains("08:00"));
        assertEquals("08:00", manager.find("user-a").orElseThrow().weatherDigestTime());
    }

    @Test
    void shouldAskForCityWhenWeatherSubscriptionLacksOne() {
        SkillResult result = skill().execute(context("订阅天气"));

        assertTrue(result.reply().contains("请告诉我要订阅天气的城市"));
    }

    @Test
    void shouldSubscribeHealthReminder() {
        SkillResult result = skill().execute(context("订阅健康提醒"));

        assertTrue(result.reply().contains("健康提醒（每天 21:00）"));
    }

    @Test
    void shouldUnsubscribeAndShowSummary() {
        ReminderSubscriptionSkill skill = skill();
        skill.execute(context("订阅天气 上海"));
        skill.execute(context("订阅健康提醒"));

        SkillResult unsubscribed = skill.execute(context("退订天气"));
        assertTrue(unsubscribed.reply().contains("已退订"));

        SkillResult summary = skill.execute(context("我的订阅"));
        assertTrue(summary.reply().contains("健康提醒"));
        assertFalse(summary.reply().contains("订阅成功"));
    }

    @Test
    void shouldSubscribeLunchMenuWithNaturalTime() {
        ReminderSubscriptionManager manager =
                new ReminderSubscriptionManager(new ObjectMapper(), tempDir.resolve("subs.json").toString());
        ReminderSubscriptionSkill skill = new ReminderSubscriptionSkill(manager);

        SkillResult result = skill.execute(context("每天12点自动生成当天的午餐菜单"));

        assertEquals(SkillResult.Status.COMPLETED, result.status());
        assertTrue(result.reply().contains("午餐菜单（每天 12:00）"));
        assertTrue(manager.find("user-a").orElseThrow().lunchMenu());
        assertEquals("12:00", manager.find("user-a").orElseThrow().lunchMenuTime());
    }

    @Test
    void shouldSubscribeLunchMenuAtCustomTime() {
        ReminderSubscriptionManager manager =
                new ReminderSubscriptionManager(new ObjectMapper(), tempDir.resolve("subs.json").toString());
        ReminderSubscriptionSkill skill = new ReminderSubscriptionSkill(manager);

        SkillResult result = skill.execute(context("订阅午餐菜单 12点半"));

        assertTrue(result.reply().contains("午餐菜单（每天 12:30）"));
        assertEquals("12:30", manager.find("user-a").orElseThrow().lunchMenuTime());
    }

    @Test
    void shouldCancelLunchMenu() {
        ReminderSubscriptionManager manager =
                new ReminderSubscriptionManager(new ObjectMapper(), tempDir.resolve("subs.json").toString());
        ReminderSubscriptionSkill skill = new ReminderSubscriptionSkill(manager);
        skill.execute(context("订阅午餐菜单"));

        SkillResult cancelled = skill.execute(context("取消午餐菜单"));

        assertTrue(cancelled.reply().contains("已取消"));
        assertTrue(manager.find("user-a").isEmpty());
    }

    @Test
    void shouldChangeLunchMenuTime() {
        ReminderSubscriptionManager manager =
                new ReminderSubscriptionManager(new ObjectMapper(), tempDir.resolve("subs.json").toString());
        manager.subscribeLunchMenu("user-a");
        ReminderSubscriptionSkill skill = new ReminderSubscriptionSkill(manager);

        SkillResult changed = skill.execute(context("午餐菜单改到12点半"));

        assertTrue(changed.reply().contains("12:30"));
        assertEquals("12:30", manager.find("user-a").orElseThrow().lunchMenuTime());
    }

    private SkillContext context(String text) {
        return new SkillContext("user-a", text, List.of(), false);
    }
}
