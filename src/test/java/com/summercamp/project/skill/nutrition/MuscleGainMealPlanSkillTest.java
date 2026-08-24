package com.summercamp.project.skill.nutrition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summercamp.project.skill.SkillContext;
import com.summercamp.project.skill.SkillResult;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MuscleGainMealPlanSkillTest {

    private MuscleGainMealPlanSkill skill;

    @BeforeEach
    void setUp() {
        skill = new MuscleGainMealPlanSkill(new FoodCatalog(new ObjectMapper()));
    }

    @Test
    void shouldMatchMealPlanIntentButNotOrdinaryQuestion() {
        assertTrue(skill.matchScore("帮我制定一个增肌饮食计划") > 0);
        assertEquals(0, skill.matchScore("增肌是什么意思？"));
    }

    @Test
    void shouldAskForCompleteProfile() {
        SkillResult result = skill.execute(context("帮我制定一个增肌饮食计划"));

        assertEquals(SkillResult.Status.WAITING_INPUT, result.status());
        assertTrue(result.reply().contains("性别：男"));
        assertTrue(result.reply().contains("健康确认"));
    }

    @Test
    void shouldGenerateTrainingAndRestDayPlansWithoutCallingAnLlm() {
        SkillResult result = skill.execute(context("""
                性别：男
                年龄：22
                身高：175cm
                体重：70kg
                日常活动：轻度
                每周训练：4次
                每次训练：60分钟
                每日餐数：4餐
                健康确认：健康成人、无食物过敏
                """));

        assertEquals(SkillResult.Status.COMPLETED, result.status());
        assertTrue(result.reply().contains("训练档次：中"));
        assertTrue(result.reply().contains("训练日目标"));
        assertTrue(result.reply().contains("休息日目标"));
        assertTrue(result.reply().contains("实际合计"));
        assertTrue(result.reply().contains("目标误差在 10% 内"));
    }

    @Test
    void shouldAcceptCommonHighActivitySynonyms() {
        SkillResult result = skill.execute(context("""
                性别：男
                年龄：20
                身高：165cm
                体重：60kg
                日常活动：重度
                每周训练：3次
                每次训练：120分钟
                每日餐数：3餐
                健康确认：健康成人、无食物过敏
                """));

        assertEquals(SkillResult.Status.COMPLETED, result.status());
        assertTrue(result.reply().contains("训练日目标"));
    }

    @Test
    void shouldReportEveryMissingOrUnrecognizedField() {
        SkillResult result = skill.execute(context("""
                性别：男
                年龄：20
                体重：60kg
                日常活动：超级活跃
                每周训练：3次
                每次训练：120分钟
                每日餐数：3餐
                健康确认：健康成人、无食物过敏
                """));

        assertEquals(SkillResult.Status.WAITING_INPUT, result.status());
        assertTrue(result.reply().contains("没有识别到以下字段"));
        assertTrue(result.reply().contains("身高"));
        assertTrue(result.reply().contains("日常活动"));
    }

    @Test
    void shouldRefuseUnsupportedHealthConditions() {
        SkillResult result = skill.execute(context("我有肾病，想要增肌饮食计划"));

        assertEquals(SkillResult.Status.COMPLETED, result.status());
        assertTrue(result.reply().contains("医生或注册营养师"));
    }

    private SkillContext context(String text) {
        return new SkillContext("user-a", text, List.of(), false);
    }
}
