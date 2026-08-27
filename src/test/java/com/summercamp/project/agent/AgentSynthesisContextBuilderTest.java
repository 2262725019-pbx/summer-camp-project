package com.summercamp.project.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

        assertTrue(context.indexOf("先记录日期") < context.indexOf("RAG_MATCHED=false"));
        assertFalse(context.contains("不可信天气"));
        assertTrue(context.contains("RAG_MATCHED=false"));
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

        assertTrue(context.contains(
                "REQUIRED_DOMAINS:\nTEMPORAL\nEXERCISE\nMEAL\nWEATHER\nLIFESTYLE"));
        assertTrue(context.contains("COMPLETED_CAPABILITIES:\nGET_WEATHER\nRUN_EXERCISE_SKILL\nRUN_MEAL_SKILL"));
        assertTrue(context.contains("WEATHER_SCOPE=THREE_DAYS"));
        assertTrue(context.contains("WEATHER:"));
        assertTrue(context.contains("location=镇江"));
        assertTrue(context.contains("scope=THREE_DAYS"));
        assertTrue(context.contains("每周训练4次，每次40分钟"));
    }

    @Test
    void failsSafelyInsteadOfCuttingGroundedSkillFactsAtHardLimit() {
        String goal = "制定饮食和运动计划";
        AgentPlan plan = groundedPlan(goal);
        AgentState state = new AgentState(plan);
        state.recordObservation(new AgentObservation("exercise", true, "运动".repeat(6_000)));
        state.recordObservation(new AgentObservation("meal", true, "饮食".repeat(6_000)));

        assertThrows(
                IllegalStateException.class,
                () -> builder.build(goal, plan, state));
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
                context.indexOf("WEATHER_SCOPE="));
        assertFalse(capabilities.contains("RUN_EXERCISE_SKILL"));
    }

    @Test
    void coordinatesRainyWeatherWithExerciseAndPreservesSkillPriority() {
        String goal = "未来7天兼顾饮食、运动和天气，每周训练4次，每次训练40分钟";
        AgentPlan plan = consistencyPlan(goal);
        AgentState state = consistencyState(plan);

        String context = builder.build(goal, plan, state);

        assertTrue(context.contains("2026-08-28 中雨"));
        assertTrue(context.contains("EXERCISE=RUN_EXERCISE_SKILL"));
        assertTrue(context.contains("MEAL=RUN_MEAL_SKILL"));
        assertTrue(context.contains("WEATHER=GET_WEATHER"));
        assertTrue(context.contains("周五快走"));
    }

    @Test
    void derivesDateWeekdayNumericAndUnqueriedWeatherMetadata() {
        String goal = "未来7天健康计划，每周训练：4次，每次训练：40分钟";
        AgentPlan plan = consistencyPlan(goal);
        AgentState state = consistencyState(plan);

        String context = builder.build(goal, plan, state);

        assertTrue(context.contains("PLAN_START_DATE=2026-08-27"));
        assertTrue(context.contains("PLAN_END_DATE=2026-09-02"));
        assertTrue(context.contains("""
                PLAN_DATE_LABELS=
                8月27日（周四）
                8月28日（周五）
                8月29日（周六）
                8月30日（周日）
                8月31日（周一）
                9月1日（周二）
                9月2日（周三）
                """));
        assertEquals(7, dateLabelCount(context));
        assertTrue(context.contains("TRAINING_FREQUENCY_PER_WEEK=4"));
        assertTrue(context.contains("TRAINING_SESSION_TOTAL_MINUTES=40"));
        assertTrue(context.contains("热身+主训练+有氧+拉伸合计必须<=40分钟"));
        assertTrue(context.contains("WEATHER_OBSERVED_THROUGH=2026-08-29"));
        assertTrue(context.contains("WEATHER_UNQUERIED_FROM=2026-08-30"));
        assertTrue(context.contains("任何具体晴雨、温度、风力都属于未知"));
        assertTrue(context.contains("不得生成或推断"));
        assertTrue(context.contains("UNQUERIED_FROM=2026-08-30"));
    }

    @Test
    void explainsEveryContextCharacterAndCompactsDuplicateSourcesDeterministically() {
        String goal = "未来7天健康计划，每周训练4次，每次训练40分钟，每天4餐";
        AgentPlan plan = fullPlan(goal);
        AgentState state = fullState(plan);

        AgentSynthesisContextBuilder.BuiltContext built =
                builder.buildDetailed(goal, plan, state);
        AgentSynthesisContextBuilder.Breakdown audit = built.breakdown();

        assertEquals(built.context().length(), audit.totalChars());
        assertEquals(audit.totalChars(), audit.explainedChars());
        assertTrue(audit.metadataChars() > 0);
        assertTrue(audit.originalGoalChars() > 0);
        assertTrue(audit.datetimeChars() > 0);
        assertTrue(audit.weatherChars() > 0);
        assertTrue(audit.exerciseChars() > 0);
        assertTrue(audit.mealChars() > 0);
        assertTrue(audit.ragChars() > 0);
        assertTrue(audit.todoChars() > 0);
        assertTrue(audit.validateChars() > 0);
        assertEquals(1, occurrences(built.context(), "完整运动主体仅出现一次"));
        assertEquals(1, occurrences(built.context(), "完整四餐主体仅出现一次"));
        assertTrue(built.context().contains("每周4次，每次40分钟"));
        assertTrue(built.context().contains("每天4餐"));
        assertTrue(built.context().contains("2026-08-26"));
        assertTrue(built.context().contains("2026-08-28"));
        assertTrue(built.context().contains("UNQUERIED_FROM=2026-08-29"));
        assertTrue(built.context().contains("TRAINING_SESSION_TOTAL_MINUTES=40"));
        assertTrue(built.context().contains("9月1日（周二）"));
        assertTrue(audit.ragChars() <= AgentSynthesisContextBuilder.MAX_SYNTHESIS_RAG_CHARS);
        assertTrue(built.context().length() <= 3_600);
        assertFalse(built.context().contains("documentIds"));
        assertFalse(built.context().contains("apiKey"));
        assertFalse(built.context().toLowerCase().contains("authorization"));
        assertFalse(built.context().contains("Bearer secret"));
        assertFalse(built.context().contains("secret"));
        assertFalse(built.context().contains("\"success\":true"));
    }

    private AgentPlan fullPlan(String goal) {
        return new AgentPlan(goal, List.of(
                step("datetime", AgentAction.GET_DATETIME),
                step("weather", AgentAction.GET_WEATHER),
                step("exercise", AgentAction.RUN_EXERCISE_SKILL),
                step("meal", AgentAction.RUN_MEAL_SKILL),
                step("rag", AgentAction.RETRIEVE_KNOWLEDGE),
                step("todo", AgentAction.CREATE_TODO),
                step("validate", AgentAction.VALIDATE),
                step("synthesis", AgentAction.SYNTHESIZE)));
    }

    private AgentState fullState(AgentPlan plan) {
        AgentState state = new AgentState(plan);
        state.recordObservation(new AgentObservation(
                "datetime", true, "日期成功", Map.of(
                "modelContent", "{\"success\":true,\"result\":{\"date\":\"2026-08-26\","
                        + "\"weekday\":\"星期三\",\"apiKey\":\"hidden\"}}")));
        state.recordObservation(new AgentObservation(
                "weather", true, "天气成功", Map.of(
                "location", "镇江市",
                "period", "THREE_DAYS",
                "modelContent", weatherEnvelope())));
        state.recordObservation(new AgentObservation(
                "exercise", true, "完整运动主体仅出现一次", Map.of(
                "reply", "完整运动主体仅出现一次：每周4次，每次40分钟；中雨日改室内自重训练。",
                "status", "COMPLETED")));
        state.recordObservation(new AgentObservation(
                "meal", true, "完整四餐主体仅出现一次", Map.of(
                "reply", "完整四餐主体仅出现一次：每天4餐，训练日增加主食和蛋白质份量。",
                "status", "COMPLETED")));
        state.recordObservation(new AgentObservation(
                "rag", true, "匹配知识", Map.of(
                "matched", "true",
                "documentIds", "[\"internal-id\"]",
                "promptContext", "Authorization: Bearer secret "
                        + "必要 evidence。".repeat(200))));
        state.recordObservation(new AgentObservation(
                "todo", true, "待办成功", Map.of(
                "modelContent", "{\"success\":true,\"result\":\"执行健康计划\"}")));
        state.recordObservation(new AgentObservation(
                "validate", true, "校验通过", Map.of("code", ValidateActionHandler.VALIDATION_PASSED)));
        return state;
    }

    private String weatherEnvelope() {
        return """
                {"success":true,"result":{"formatted_text":"重复天气文本","data":{
                  "location":"镇江市","reportTime":"2026-08-26 16:00:00","period":"THREE_DAYS",
                  "forecasts":[
                    {"date":"2026-08-26","dayWeather":"晴","nightWeather":"多云","dayTemperature":"33","nightTemperature":"25","dayWind":"南","dayPower":"3"},
                    {"date":"2026-08-27","dayWeather":"小雨","nightWeather":"阴","dayTemperature":"30","nightTemperature":"24","dayWind":"东","dayPower":"3"},
                    {"date":"2026-08-28","dayWeather":"中雨","nightWeather":"小雨","dayTemperature":"28","nightTemperature":"23","dayWind":"北","dayPower":"4"}
                  ]}}}
                """;
    }

    private int occurrences(String text, String value) {
        return text.split(java.util.regex.Pattern.quote(value), -1).length - 1;
    }

    private long dateLabelCount(String context) {
        int start = context.indexOf("PLAN_DATE_LABELS=\n");
        int end = context.indexOf("TRAINING_FREQUENCY_PER_WEEK=", start);
        return context.substring(start, end).lines()
                .filter(line -> line.matches("\\d{1,2}月\\d{1,2}日（周[一二三四五六日]）"))
                .count();
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
                Map.of("modelContent", "{\"success\":true,\"result\":{\"date\":\"2026-08-27\"}}")
        ));
        state.recordObservation(new AgentObservation(
                "weather",
                true,
                "天气查询成功",
                Map.of(
                        "location", "镇江",
                        "period", "THREE_DAYS",
                        "modelContent", "2026-08-27 多云；2026-08-28 中雨；2026-08-29 晴"
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
