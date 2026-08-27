package com.summercamp.project.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summercamp.project.llm.AgentProviderException;
import com.summercamp.project.llm.AgentProviderFailureCategory;
import com.summercamp.project.skill.SkillRegistry;
import com.summercamp.project.skill.health.ExerciseHealthAdviceSkill;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AgentProviderIndependentFallbackIntegrationTest {
    private static final String GOAL = """
            请帮我制定未来7天的大学生健康生活规划。
            所在地：镇江市
            性别：男
            年龄：22
            身高：175cm
            体重：70kg
            日常活动：轻度
            每周训练：4次
            每次训练：60分钟
            每日餐数：4餐
            健康确认：健康成人、无食物过敏
            运动目标：增肌
            运动偏好：快走和自重训练
            希望兼顾天气、运动、饮食和作息。
            """;

    @Test
    void fullProviderOutageStillCompletesThroughAllApplicationGates() {
        AtomicInteger datetimeCalls = new AtomicInteger();
        AtomicInteger weatherCalls = new AtomicInteger();
        AtomicInteger mealCalls = new AtomicInteger();
        LlmAgentPlanner planner = new LlmAgentPlanner((goal, instructions) -> {
            throw new AgentProviderException(
                    "PLANNING", AgentProviderFailureCategory.TIMEOUT, null);
        }, new ObjectMapper());
        ExerciseHealthAdviceSkill exerciseSkill = new ExerciseHealthAdviceSkill(
                (request, context) -> {
                    throw new AgentProviderException(
                            "EXERCISE", AgentProviderFailureCategory.CONNECTIVITY, null);
                });
        AgentSynthesisClient unavailableSynthesis = (goal, context) -> {
            throw new AgentProviderException(
                    "SYNTHESIS", AgentProviderFailureCategory.RATE_LIMIT, null);
        };
        AgentActionHandlerRegistry handlers = new AgentActionHandlerRegistry(List.of(
                handler(AgentAction.GET_DATETIME, (step, context) -> {
                    datetimeCalls.incrementAndGet();
                    context.metrics().recordToolCall("get_current_datetime");
                    return new AgentObservation(
                            step.id(), true, "当前日期：2026-08-27，周四");
                }),
                handler(AgentAction.GET_WEATHER, (step, context) -> {
                    weatherCalls.incrementAndGet();
                    context.metrics().recordToolCall("get_weather");
                    return new AgentObservation(
                            step.id(),
                            true,
                            "2026-08-27 晴；2026-08-28 阴；2026-08-29 小雨",
                            Map.of(
                                    "tool", "get_weather",
                                    "location", "镇江市",
                                    "period", "THREE_DAYS",
                                    "modelContent",
                                    "2026-08-27 晴；2026-08-28 阴；2026-08-29 小雨"));
                }),
                new ExerciseSkillAgentActionHandler(new SkillRegistry(List.of(exerciseSkill))),
                handler(AgentAction.RUN_MEAL_SKILL, (step, context) -> {
                    mealCalls.incrementAndGet();
                    context.metrics().recordSkillCall(AgentAction.RUN_MEAL_SKILL);
                    return new AgentObservation(
                            step.id(),
                            true,
                            "每日四餐，均衡安排主食、蛋白质、蔬菜和加餐。",
                            Map.of(
                                    "skill", "muscle-gain-meal-plan",
                                    "status", "COMPLETED",
                                    "reply", "每日四餐，均衡安排主食、蛋白质、蔬菜和加餐。"));
                }),
                new ValidateActionHandler(),
                new SynthesizeActionHandler(
                        new AgentSynthesisContextBuilder(), unavailableSynthesis)
        ));
        AgentRunMetricsCollector collector = new AgentRunMetricsCollector();
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                planner, new AgentExecutor(handlers), () -> collector);

        AgentRunResult result = orchestrator.run(
                new AgentRunRequest("user", GOAL, List.of(), false), collector);
        AgentRunMetricsSnapshot metrics = collector.snapshot();

        assertEquals(AgentRunResult.Status.COMPLETED, result.status());
        assertEquals(1, metrics.deterministicPlannerFallbackCount());
        assertEquals(1, metrics.deterministicExerciseFallbackCount());
        assertEquals(1, metrics.deterministicSynthesisFallbackCount());
        assertEquals(1, datetimeCalls.get());
        assertEquals(1, weatherCalls.get());
        assertEquals(1, mealCalls.get());
        assertEquals(1, metrics.dateTimeToolCallCount());
        assertEquals(1, metrics.weatherToolCallCount());
        assertEquals(0, metrics.todoToolCallCount());
        assertEquals(1, metrics.exerciseSkillCallCount());
        assertEquals(1, metrics.weatherReuseEligibleCount());
        assertEquals(1, metrics.weatherReuseAppliedCount());
        assertEquals(1, metrics.finalValidationAttemptCount());
        assertEquals(0, metrics.finalValidationFailureCount());
        assertEquals(4, occurrences(result.reply(), "- 正式训练："));
        assertTrue(result.reply().contains("8月27日（周四）"));
        assertTrue(result.reply().contains("9月2日（周三）"));
        assertTrue(result.reply().contains(
                "未获取实时天气，建议当天查看天气后决定是否户外"));
        assertFalse(result.reply().contains("audit"));
        assertFalse(result.reply().contains("provider"));
        assertFalse(result.reply().contains("fallback"));
    }

    private int occurrences(String source, String needle) {
        return source.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    private AgentActionHandler handler(
            AgentAction action,
            java.util.function.BiFunction<AgentStep, AgentExecutionContext, AgentObservation> operation
    ) {
        return new AgentActionHandler() {
            @Override
            public AgentAction action() {
                return action;
            }

            @Override
            public AgentObservation execute(AgentStep step, AgentExecutionContext context) {
                return operation.apply(step, context);
            }
        };
    }
}
