package com.summercamp.project.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentSynthesisContextBuilderTest {
    private final AgentSynthesisContextBuilder builder = new AgentSynthesisContextBuilder();

    @Test
    void preservesPlanOrderAndExcludesFailedObservations() {
        AgentPlan plan = plan();
        AgentState state = new AgentState(plan);
        state.recordObservation(new AgentObservation("datetime", true, "先记录日期"));
        state.recordObservation(new AgentObservation("weather", false, "不可信天气 99 度"));
        state.recordObservation(new AgentObservation("rag", true, "后记录知识", Map.of("matched", "false")));

        String context = builder.build(plan.goal(), plan, state);

        assertTrue(context.indexOf("先记录日期") < context.indexOf("后记录知识"));
        assertFalse(context.contains("不可信天气"));
        assertTrue(context.contains("matched：false"));
        assertFalse(context.contains("datetime"));
    }

    @Test
    void enforcesPerObservationAndTotalLengthAndRedactsUnsafeData() {
        AgentPlan plan = plan();
        AgentState state = new AgentState(plan);
        String longValue = "内容".repeat(3_000);
        state.recordObservation(new AgentObservation(
                "datetime",
                true,
                longValue,
                Map.of("apiKey", "super-secret", "imageBase64", "data:image/png;base64,AAAA")));
        state.recordObservation(new AgentObservation("weather", true, longValue));
        state.recordObservation(new AgentObservation("rag", true, longValue));

        String context = builder.build(plan.goal(), plan, state);

        assertTrue(context.length() <= AgentSynthesisContextBuilder.MAX_TOTAL_CHARS);
        assertFalse(context.contains("super-secret"));
        assertFalse(context.contains("AAAA"));
    }

    @Test
    void exposesDeterministicGroundingMetadataAndWeatherScope() {
        String goal = "未来7天兼顾饮食、运动、作息和天气，每周训练4次，每次40分钟";
        AgentPlan plan = groundedPlan(goal);
        AgentState state = new AgentState(plan);
        state.recordObservation(new AgentObservation(
                "weather",
                true,
                "天气查询成功",
                Map.of(
                        "location", "镇江",
                        "period", "THREE_DAYS",
                        "modelContent", "未来三天：多云、阵雨、晴"
                )
        ));
        state.recordObservation(new AgentObservation("exercise", true, "每周训练4次"));
        state.recordObservation(new AgentObservation("meal", true, "四餐饮食方案"));

        String context = builder.build(goal, plan, state);

        assertTrue(context.contains("REQUIRED_DOMAINS:\nEXERCISE\nMEAL\nWEATHER\nLIFESTYLE"));
        assertTrue(context.contains("COMPLETED_CAPABILITIES:\nGET_WEATHER\nRUN_EXERCISE_SKILL\nRUN_MEAL_SKILL"));
        assertTrue(context.contains("WEATHER_SCOPE:\nTHREE_DAYS"));
        assertTrue(context.contains("[真实天气观测]"));
        assertTrue(context.contains("查询地点：镇江"));
        assertTrue(context.contains("查询范围：THREE_DAYS"));
        assertTrue(context.contains("超出范围的日期没有实时天气数据"));
        assertTrue(context.contains("不得推断为晴、雨、温度或其他具体天气"));
        assertTrue(context.contains("每周训练4次，每次40分钟"));
    }

    @Test
    void missingExerciseObservationIsNotReportedAsCompletedCapability() {
        String goal = "制定饮食、运动和天气计划";
        AgentPlan plan = groundedPlan(goal);
        AgentState state = new AgentState(plan);
        state.recordObservation(new AgentObservation(
                "weather", true, "三日天气",
                Map.of("location", "镇江", "period", "THREE_DAYS", "modelContent", "三日天气")));
        state.recordObservation(new AgentObservation("meal", true, "饮食方案"));

        String context = builder.build(goal, plan, state);

        String capabilities = context.substring(
                context.indexOf("COMPLETED_CAPABILITIES:"),
                context.indexOf("WEATHER_SCOPE:"));
        assertFalse(capabilities.contains("RUN_EXERCISE_SKILL"));
        assertTrue(context.contains("不得补写 COMPLETED_CAPABILITIES 中不存在的详细 Skill 方案"));
    }

    @Test
    void coordinatesRainyWeatherWithExerciseAndPreservesSkillPriority() {
        String goal = "未来7天兼顾饮食、运动和天气，每周训练4次，每次训练40分钟";
        AgentPlan plan = consistencyPlan(goal);
        AgentState state = consistencyState(plan);

        String context = builder.build(goal, plan, state);

        assertTrue(context.contains("2026-08-28 中雨"));
        assertTrue(context.contains("该日在所有运动章节都必须改为室内步行、自重训练、健身房等室内等价方案"));
        assertTrue(context.contains("不得再标为户外"));
        assertTrue(context.contains("FORMAL_EXERCISE_PLAN=RUN_EXERCISE_SKILL"));
        assertTrue(context.contains("FORMAL_MEAL_PLAN=RUN_MEAL_SKILL"));
        assertTrue(context.contains("WEATHER_FACTS=GET_WEATHER"));
        assertTrue(context.contains("保留运动内容但按天气事实调整为室内等价方案"));
        assertTrue(context.contains("周五快走"));
    }

    @Test
    void derivesDateWeekdayNumericAndUnqueriedWeatherMetadata() {
        String goal = "未来7天健康计划，每周训练：4次，每次训练：40分钟";
        AgentPlan plan = consistencyPlan(goal);
        AgentState state = consistencyState(plan);

        String context = builder.build(goal, plan, state);

        assertTrue(context.contains("PLAN_START_DATE=2026-08-26"));
        assertTrue(context.contains("PLAN_END_DATE=2026-09-01"));
        assertTrue(context.contains("8月26日（周三）"));
        assertTrue(context.contains("8月28日（周五）"));
        assertTrue(context.contains("8月31日（周一）"));
        assertTrue(context.contains("9月1日（周二）"));
        assertTrue(context.contains("每日安排优先写完整日期（星期）"));
        assertTrue(context.contains("TRAINING_FREQUENCY_PER_WEEK=4"));
        assertTrue(context.contains("TRAINING_DURATION_MINUTES=40"));
        assertTrue(context.contains("WEATHER_OBSERVED_THROUGH=2026-08-28"));
        assertTrue(context.contains("WEATHER_UNQUERIED_FROM=2026-08-29"));
        assertTrue(context.contains("从 8月29日（周六） 起至计划结束均未获取实时天气"));
        assertTrue(context.contains("非训练日活动必须明确标为恢复/日常活动"));
    }

    private AgentPlan plan() {
        return new AgentPlan("健康目标", List.of(
                step("datetime", AgentAction.GET_DATETIME),
                step("weather", AgentAction.GET_WEATHER),
                step("rag", AgentAction.RETRIEVE_KNOWLEDGE),
                step("validate", AgentAction.VALIDATE),
                step("synthesis", AgentAction.SYNTHESIZE)
        ));
    }

    private AgentPlan groundedPlan(String goal) {
        return new AgentPlan(goal, List.of(
                step("weather", AgentAction.GET_WEATHER),
                step("exercise", AgentAction.RUN_EXERCISE_SKILL),
                step("meal", AgentAction.RUN_MEAL_SKILL),
                step("validate", AgentAction.VALIDATE),
                step("synthesis", AgentAction.SYNTHESIZE)
        ));
    }

    private AgentPlan consistencyPlan(String goal) {
        return new AgentPlan(goal, List.of(
                step("datetime", AgentAction.GET_DATETIME),
                step("weather", AgentAction.GET_WEATHER),
                step("exercise", AgentAction.RUN_EXERCISE_SKILL),
                step("meal", AgentAction.RUN_MEAL_SKILL),
                step("validate", AgentAction.VALIDATE),
                step("synthesis", AgentAction.SYNTHESIZE)
        ));
    }

    private AgentState consistencyState(AgentPlan plan) {
        AgentState state = new AgentState(plan);
        state.recordObservation(new AgentObservation(
                "datetime",
                true,
                "日期查询成功",
                Map.of("modelContent", "{\"success\":true,\"result\":{\"date\":\"2026-08-26\"}}")
        ));
        state.recordObservation(new AgentObservation(
                "weather",
                true,
                "天气查询成功",
                Map.of(
                        "location", "镇江",
                        "period", "THREE_DAYS",
                        "modelContent", "2026-08-26 多云；2026-08-27 阴；2026-08-28 中雨"
                )
        ));
        state.recordObservation(new AgentObservation("exercise", true, "周五快走，原方案为户外"));
        state.recordObservation(new AgentObservation("meal", true, "四餐饮食安排"));
        return state;
    }

    private AgentStep step(String id, AgentAction action) {
        return new AgentStep(id, action, "执行", "原因", List.of());
    }
}
