package com.summercamp.project.skill.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.summercamp.project.llm.ChatOutcome;
import com.summercamp.project.llm.ChatProviderPolicy;
import com.summercamp.project.llm.ChatRequest;
import com.summercamp.project.skill.SkillContext;
import com.summercamp.project.skill.SkillExecutionMode;
import com.summercamp.project.skill.SkillResult;
import com.summercamp.project.skill.SkillTrustedContext;
import com.summercamp.project.skill.TrustedWeatherObservation;
import com.summercamp.project.tool.ToolContext;
import com.summercamp.project.weather.WeatherPeriod;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ExerciseHealthAdviceSkillTest {

    @Test
    void shouldInjectInstructionsAndWaitForMissingInformation() {
        AtomicReference<ChatRequest> captured = new AtomicReference<>();
        ExerciseHealthAdviceSkill skill = new ExerciseHealthAdviceSkill((request, context) -> {
            captured.set(request);
            return ChatOutcome.text("你的主要运动目标是什么？每周可以运动几次？");
        });

        SkillResult result = skill.execute(context("帮我制定运动计划"));

        assertEquals(SkillResult.Status.WAITING_INPUT, result.status());
        assertTrue(captured.get().groundingContext().contains("运动健康建议 Skill"));
        assertTrue(captured.get().disabledTools().isEmpty());
        assertEquals(ChatProviderPolicy.STANDARD, captured.get().providerPolicy());
        assertFalse(captured.get().groundingContext()
                .contains("CURRENT_RUN_TRUSTED_GET_WEATHER_OBSERVATION"));
    }

    @Test
    void shouldSelectBoundedProviderPolicyOnlyForTypedAgentExecution() {
        AtomicReference<ChatRequest> captured = new AtomicReference<>();
        ExerciseHealthAdviceSkill skill = new ExerciseHealthAdviceSkill((request, context) -> {
            captured.set(request);
            return ChatOutcome.text("运动计划\n【会话结束】");
        });
        SkillContext context = new SkillContext(
                "user-a",
                "制定运动计划",
                List.of(),
                false,
                null,
                SkillTrustedContext.empty(),
                SkillExecutionMode.AGENT);

        skill.execute(context);

        assertEquals(
                ChatProviderPolicy.AGENT_EXERCISE_SKILL_BOUNDED,
                captured.get().providerPolicy());
    }

    @Test
    void shouldInjectCurrentRunWeatherAsSystemGroundingAndDisableOnlyWeatherTool() {
        AtomicReference<ChatRequest> captured = new AtomicReference<>();
        ExerciseHealthAdviceSkill skill = new ExerciseHealthAdviceSkill((request, context) -> {
            captured.set(request);
            return ChatOutcome.text("室内运动计划\n【会话结束】");
        });
        TrustedWeatherObservation weather = new TrustedWeatherObservation(
                "镇江",
                WeatherPeriod.THREE_DAYS,
                "{\"success\":true,\"result\":\"未来三日小雨转多云\"}");
        SkillContext context = new SkillContext(
                "user-a",
                "为我制定七天运动计划",
                List.of(),
                false,
                null,
                SkillTrustedContext.withWeather(weather));

        SkillResult result = skill.execute(context);

        assertEquals(SkillResult.Status.COMPLETED, result.status());
        assertEquals("为我制定七天运动计划", captured.get().text());
        assertEquals(java.util.Set.of("get_weather"), captured.get().disabledTools());
        assertTrue(captured.get().groundingContext().contains("SOURCE=get_weather"));
        assertTrue(captured.get().groundingContext().contains("PERIOD=THREE_DAYS"));
        assertTrue(captured.get().groundingContext().contains("不要再次调用 get_weather"));
        assertTrue(captured.get().groundingContext().contains("第 4 天及以后视为未查询"));
    }

    @Test
    void shouldCompleteWhenModelEmitsEndMarker() {
        ExerciseHealthAdviceSkill skill = new ExerciseHealthAdviceSkill(
                (request, context) -> ChatOutcome.text("每周训练三次，每次四十分钟。\n【会话结束】"));

        SkillResult result = skill.execute(context("目标减脂，每周三次"));

        assertEquals(SkillResult.Status.COMPLETED, result.status());
        assertFalse(result.reply().contains("会话结束"));
    }

    @Test
    void shouldRecognizeExerciseRequestsWithoutTakingDietPlan() {
        ExerciseHealthAdviceSkill skill = new ExerciseHealthAdviceSkill(
                (request, context) -> ChatOutcome.text("reply"));

        assertTrue(skill.matchScore("请帮我安排一个健身计划") > 0);
        assertEquals(0, skill.matchScore("帮我制定一个增肌饮食计划"));
    }

    private SkillContext context(String text) {
        return new SkillContext("user-a", text, List.of(), false);
    }
}
