package com.summercamp.project.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class AgentFinalPlanValidatorTest {
    private final AgentFinalPlanValidator validator = new AgentFinalPlanValidator();

    @Test
    void missingOneRequiredDateFailsWithoutPartialDateMatches() {
        FinalPlanValidationResult result = validate("""
                8/27（周四）
                8/28（周五）
                8/29（周六）
                8/30（周日）
                9/1（周二）
                9/2（周三）
                备注：8/2 不能代表本计划中被遗漏的日期。
                """);

        assertFalse(result.valid());
        assertEquals(List.of(FinalPlanValidationIssueCode.MISSING_PLAN_DATE), result.issues());
    }

    @Test
    void acceptsAllRequiredDatesInSupportedFormats() {
        FinalPlanValidationResult result = validate("""
                2026年8月27日（周四）
                2026-08-28（周五）
                8/29（周六）
                8月30日（周日）
                8月31日（周一）
                9月1日（周二）
                9/2（周三）
                """);

        assertTrue(result.valid());
    }

    @Test
    void rejectsWrongWeekdayAndAcceptsCorrectWeekday() {
        String dates = completeAnswer();
        FinalPlanValidationResult wrong = validate(dates.replace("9月1日（周二）", "9月1日（周一）"));
        FinalPlanValidationResult correct = validate(dates);

        assertTrue(wrong.issues().contains(FinalPlanValidationIssueCode.WRONG_WEEKDAY));
        assertTrue(correct.valid());
    }

    @Test
    void rejectsConcreteWeatherAfterObservedScope() {
        FinalPlanValidationResult result = validate(completeAnswer().replace(
                "8月30日（周日）：恢复。",
                "8月30日（周日）：晴，32℃，东南风3级。"));

        assertTrue(result.issues().contains(
                FinalPlanValidationIssueCode.CONCRETE_WEATHER_OUTSIDE_SCOPE));
    }

    @Test
    void acceptsUnknownWeatherGuidanceOutsideScope() {
        FinalPlanValidationResult result = validate(completeAnswer().replace(
                "8月30日（周日）：恢复。",
                "8月30日（周日）：未获取实时天气，建议当天查看天气。"));

        assertTrue(result.valid());
    }

    @Test
    void acceptsConcreteWeatherOnObservedDate() {
        FinalPlanValidationResult result = validate(completeAnswer().replace(
                "8月29日（周六）：安排。",
                "8月29日（周六）：小雨，33℃。"));

        assertTrue(result.valid());
    }

    private FinalPlanValidationResult validate(String answer) {
        AgentPlan plan = new AgentPlan("未来7天健康规划", List.of());
        AgentState state = new AgentState(plan);
        return validator.validate(
                plan.goal(),
                plan,
                state,
                """
                        PLAN_START_DATE=2026-08-27
                        PLAN_END_DATE=2026-09-02
                        WEATHER_OBSERVED_THROUGH=2026-08-29
                        WEATHER_UNQUERIED_FROM=2026-08-30
                        """,
                answer);
    }

    private String completeAnswer() {
        return """
                8月27日（周四）：安排。
                8月28日（周五）：安排。
                8月29日（周六）：安排。
                8月30日（周日）：恢复。
                8月31日（周一）：安排。
                9月1日（周二）：安排。
                9月2日（周三）：安排。
                """;
    }
}
