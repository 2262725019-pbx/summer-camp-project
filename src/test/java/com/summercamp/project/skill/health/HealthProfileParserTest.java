package com.summercamp.project.skill.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.summercamp.project.skill.health.HealthProfileParser.Goal;
import com.summercamp.project.skill.health.HealthProfileParser.ParseResult;
import com.summercamp.project.skill.health.HealthProfileParser.Profile;
import java.util.List;
import org.junit.jupiter.api.Test;

class HealthProfileParserTest {

    @Test
    void shouldExtractProfileFromOneSentenceGoal() {
        ParseResult result = HealthProfileParser.parse(
                "我是大学生 175cm 70kg，想一个月减 8 斤，我在北京，喜欢跑步");

        Profile profile = result.profile();
        assertEquals(Goal.CUT, profile.goal());
        assertEquals(175.0, profile.heightCm());
        assertEquals(70.0, profile.weightKg());
        assertEquals(30, profile.periodDays());
        assertEquals(-4.0, profile.weightDeltaKg());
        assertEquals("北京", profile.city());
        assertEquals("跑步", profile.trainingPreference());
        assertTrue(result.missingCritical().contains("性别"));
    }

    @Test
    void shouldExtractLabeledProfileWithoutMissingFields() {
        ParseResult result = HealthProfileParser.parse(
                "性别：男 年龄：22 身高：175cm 体重：70kg 想增肌 每天4餐");

        Profile profile = result.profile();
        assertEquals(Goal.BULK, profile.goal());
        assertEquals(Boolean.TRUE, profile.male());
        assertEquals(22, profile.age());
        assertEquals(175.0, profile.heightCm());
        assertEquals(70.0, profile.weightKg());
        assertEquals(4, profile.mealsPerDay());
        assertTrue(result.missingCritical().isEmpty());
    }

    @Test
    void shouldRecognizeFemaleWord() {
        ParseResult result = HealthProfileParser.parse("女生 165cm 50kg 想减脂");

        assertEquals(Boolean.FALSE, result.profile().male());
        assertEquals(Goal.CUT, result.profile().goal());
        assertTrue(result.missingCritical().isEmpty());
    }

    @Test
    void shouldDefaultToMaintainWhenNoGoalTerm() {
        ParseResult result = HealthProfileParser.parse("帮我制定健康生活规划");

        assertEquals(Goal.MAINTAIN, result.profile().goal());
        assertEquals(List.of("性别", "身高", "体重"), result.missingCritical());
        assertEquals(30, result.profile().periodDays());
    }

    @Test
    void shouldConvertWeightDeltaUnitsToKilograms() {
        assertEquals(-4.0, HealthProfileParser.parse("减8斤").profile().weightDeltaKg());
        assertEquals(-3.5, HealthProfileParser.parse("减3.5公斤").profile().weightDeltaKg());
        assertEquals(5.0, HealthProfileParser.parse("增5公斤").profile().weightDeltaKg());
        assertEquals(2.5, HealthProfileParser.parse("增5斤").profile().weightDeltaKg());
        assertNull(HealthProfileParser.parse("健康生活规划").profile().weightDeltaKg());
    }

    @Test
    void shouldConvertPeriodToDays() {
        assertEquals(10, HealthProfileParser.parse("10天").profile().periodDays());
        assertEquals(14, HealthProfileParser.parse("两周").profile().periodDays());
        assertEquals(90, HealthProfileParser.parse("3个月").profile().periodDays());
        assertEquals(30, HealthProfileParser.parse("健康生活规划").profile().periodDays());
    }

    @Test
    void shouldExtractCityWithOrWithoutSuffix() {
        assertEquals("江西省宜春市", HealthProfileParser.parse("我在江西省宜春市").profile().city());
        assertEquals("北京", HealthProfileParser.parse("我在北京上学").profile().city());
        assertNull(HealthProfileParser.parse("想一个月减 8 斤").profile().city());
    }
}
