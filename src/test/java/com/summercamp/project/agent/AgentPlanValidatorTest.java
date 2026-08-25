package com.summercamp.project.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentPlanValidatorTest {
    private final AgentPlanValidator validator = new AgentPlanValidator();

    @Test
    void acceptsValidHealthAgentPlan() {
        AgentPlanValidationResult result = validator.validate(validHealthPlan());

        assertTrue(result.valid(), () -> "Unexpected validation errors: " + result.errors());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void rejectsPlanWithOnlyOneStep() {
        AgentPlan plan = plan(step("datetime", AgentAction.GET_DATETIME));

        assertInvalidWith(plan, "between 3 and 12");
    }

    @Test
    void rejectsDuplicateStepIds() {
        AgentPlan plan = plan(
                step("same", AgentAction.GET_DATETIME),
                step("same", AgentAction.GET_WEATHER),
                step("knowledge", AgentAction.RETRIEVE_KNOWLEDGE),
                step("final", AgentAction.SYNTHESIZE, "knowledge")
        );

        assertInvalidWith(plan, "Duplicate step id");
    }

    @Test
    void rejectsMissingDependency() {
        AgentPlan plan = plan(
                step("datetime", AgentAction.GET_DATETIME, "missing"),
                step("weather", AgentAction.GET_WEATHER),
                step("knowledge", AgentAction.RETRIEVE_KNOWLEDGE),
                step("final", AgentAction.SYNTHESIZE, "knowledge")
        );

        assertInvalidWith(plan, "unknown dependency");
    }

    @Test
    void rejectsSelfDependency() {
        AgentPlan plan = plan(
                step("datetime", AgentAction.GET_DATETIME, "datetime"),
                step("weather", AgentAction.GET_WEATHER),
                step("knowledge", AgentAction.RETRIEVE_KNOWLEDGE),
                step("final", AgentAction.SYNTHESIZE, "knowledge")
        );

        assertInvalidWith(plan, "depend on itself");
    }

    @Test
    void rejectsDependencyCycle() {
        AgentPlan plan = plan(
                step("datetime", AgentAction.GET_DATETIME, "weather"),
                step("weather", AgentAction.GET_WEATHER, "datetime"),
                step("knowledge", AgentAction.RETRIEVE_KNOWLEDGE),
                step("final", AgentAction.SYNTHESIZE, "knowledge")
        );

        assertInvalidWith(plan, "cycle");
    }

    @Test
    void rejectsPlanWithoutSynthesize() {
        AgentPlan plan = plan(
                step("datetime", AgentAction.GET_DATETIME),
                step("weather", AgentAction.GET_WEATHER),
                step("knowledge", AgentAction.RETRIEVE_KNOWLEDGE)
        );

        assertInvalidWith(plan, "must contain one SYNTHESIZE");
    }

    @Test
    void rejectsSynthesizeWithoutDependency() {
        AgentPlan plan = plan(
                step("datetime", AgentAction.GET_DATETIME),
                step("weather", AgentAction.GET_WEATHER),
                step("knowledge", AgentAction.RETRIEVE_KNOWLEDGE),
                step("final", AgentAction.SYNTHESIZE)
        );

        assertInvalidWith(plan, "must have at least one dependency");
    }

    @Test
    void rejectsTwoSynthesizeSteps() {
        AgentPlan plan = plan(
                step("datetime", AgentAction.GET_DATETIME),
                step("weather", AgentAction.GET_WEATHER),
                step("knowledge", AgentAction.RETRIEVE_KNOWLEDGE),
                step("final-1", AgentAction.SYNTHESIZE, "datetime"),
                step("final-2", AgentAction.SYNTHESIZE, "weather")
        );

        assertInvalidWith(plan, "at most one SYNTHESIZE");
    }

    @Test
    void rejectsPlanOverMaximumStepCount() {
        List<AgentStep> steps = new ArrayList<>();
        for (int index = 0; index < AgentPlanValidator.MAX_STEPS - 1; index++) {
            AgentAction action = switch (index % 3) {
                case 0 -> AgentAction.GET_DATETIME;
                case 1 -> AgentAction.GET_WEATHER;
                default -> AgentAction.RETRIEVE_KNOWLEDGE;
            };
            steps.add(step("business-" + index, action));
        }
        steps.add(step("validate", AgentAction.VALIDATE, "business-0"));
        steps.add(step("final", AgentAction.SYNTHESIZE, "validate"));

        assertInvalidWith(new AgentPlan("健康生活规划", steps), "between 3 and 12");
    }

    @Test
    void rejectsBlankIdNullActionAndBlankDescription() {
        AgentPlan plan = new AgentPlan("健康生活规划", List.of(
                new AgentStep(" ", AgentAction.GET_DATETIME, "读取时间", "安排作息", List.of()),
                new AgentStep("weather", null, "读取天气", "安排户外活动", List.of()),
                new AgentStep("knowledge", AgentAction.RETRIEVE_KNOWLEDGE, " ", "补充知识", List.of()),
                step("meal", AgentAction.RUN_MEAL_SKILL),
                step("final", AgentAction.SYNTHESIZE, "meal")
        ));

        AgentPlanValidationResult result = validator.validate(plan);

        assertFalse(result.valid());
        assertContains(result, "non-blank id");
        assertContains(result, "must have an AgentAction");
        assertContains(result, "non-blank description");
    }

    @Test
    void rejectsFewerThanThreeDistinctBusinessTaskActions() {
        AgentPlan plan = plan(
                step("weather-1", AgentAction.GET_WEATHER),
                step("weather-2", AgentAction.GET_WEATHER),
                step("validate", AgentAction.VALIDATE, "weather-1"),
                step("final", AgentAction.SYNTHESIZE, "validate")
        );

        assertInvalidWith(plan, "distinct business task actions");
    }

    @Test
    void rejectsSynthesizeAsFirstStep() {
        AgentPlan plan = plan(
                step("final", AgentAction.SYNTHESIZE, "datetime"),
                step("datetime", AgentAction.GET_DATETIME),
                step("weather", AgentAction.GET_WEATHER),
                step("knowledge", AgentAction.RETRIEVE_KNOWLEDGE)
        );

        assertInvalidWith(plan, "first independent step");
    }

    @Test
    void rejectsSynthesizeWithoutTransitiveBusinessDependency() {
        AgentPlan plan = plan(
                step("datetime", AgentAction.GET_DATETIME),
                step("weather", AgentAction.GET_WEATHER),
                step("knowledge", AgentAction.RETRIEVE_KNOWLEDGE),
                step("validate", AgentAction.VALIDATE),
                step("final", AgentAction.SYNTHESIZE, "validate")
        );

        assertInvalidWith(plan, "business execution step");
    }

    @Test
    void rejectsBusinessExecutionAfterSynthesize() {
        AgentPlan plan = plan(
                step("datetime", AgentAction.GET_DATETIME),
                step("weather", AgentAction.GET_WEATHER),
                step("knowledge", AgentAction.RETRIEVE_KNOWLEDGE),
                step("final", AgentAction.SYNTHESIZE, "knowledge"),
                step("meal", AgentAction.RUN_MEAL_SKILL)
        );

        assertInvalidWith(plan, "must not follow SYNTHESIZE");
    }

    @Test
    void returnsStructuredErrorForNullPlan() {
        AgentPlanValidationResult result = validator.validate(null);

        assertFalse(result.valid());
        assertContains(result, "Plan must not be null");
    }

    @Test
    void planRejectsBlankGoal() {
        assertThrows(IllegalArgumentException.class, () -> new AgentPlan(" ", List.of()));
    }

    @Test
    void rejectsMissingRequiredActionInput() {
        AgentStep invalidWeather = new AgentStep(
                "weather",
                AgentAction.GET_WEATHER,
                "读取天气",
                "安排活动",
                List.of(),
                Map.of("period", "THREE_DAYS")
        );

        assertInvalidWith(plan(
                invalidWeather,
                step("knowledge", AgentAction.RETRIEVE_KNOWLEDGE),
                step("exercise", AgentAction.RUN_EXERCISE_SKILL, "weather", "knowledge"),
                step("final", AgentAction.SYNTHESIZE, "exercise")
        ), "requires non-blank input: location");
    }

    @Test
    void rejectsUnsupportedInputAndWeatherPeriod() {
        AgentStep invalidWeather = new AgentStep(
                "weather",
                AgentAction.GET_WEATHER,
                "读取天气",
                "安排活动",
                List.of(),
                Map.of("location", "镇江", "period", "SEVEN_DAYS", "apiKey", "secret")
        );

        AgentPlanValidationResult result = validator.validate(plan(
                step("datetime", AgentAction.GET_DATETIME),
                invalidWeather,
                step("knowledge", AgentAction.RETRIEVE_KNOWLEDGE),
                step("final", AgentAction.SYNTHESIZE, "weather", "knowledge")
        ));

        assertFalse(result.valid());
        assertContains(result, "unsupported input");
        assertContains(result, "period must be one of");
    }

    private AgentPlan validHealthPlan() {
        return plan(
                step("datetime", AgentAction.GET_DATETIME),
                step("weather", AgentAction.GET_WEATHER, "datetime"),
                step("knowledge", AgentAction.RETRIEVE_KNOWLEDGE),
                step("exercise", AgentAction.RUN_EXERCISE_SKILL, "weather", "knowledge"),
                step("meal", AgentAction.RUN_MEAL_SKILL, "knowledge"),
                step("validate", AgentAction.VALIDATE, "exercise", "meal"),
                step("final", AgentAction.SYNTHESIZE, "validate")
        );
    }

    private AgentPlan plan(AgentStep... steps) {
        return new AgentPlan("制定大学生智能健康生活规划", List.of(steps));
    }

    private AgentStep step(String id, AgentAction action, String... dependencies) {
        return new AgentStep(
                id,
                action,
                "执行 " + id,
                "为健康生活计划提供信息",
                List.of(dependencies),
                defaultInputs(action)
        );
    }

    private Map<String, String> defaultInputs(AgentAction action) {
        if (action == null) {
            return Map.of();
        }
        return switch (action) {
            case GET_DATETIME, VALIDATE, SYNTHESIZE -> Map.of();
            case GET_WEATHER -> Map.of("location", "镇江", "period", "THREE_DAYS");
            case RETRIEVE_KNOWLEDGE -> Map.of("query", "大学生健康生活");
            case RUN_EXERCISE_SKILL, RUN_MEAL_SKILL -> Map.of("request", "健康生活规划");
            case CALCULATE -> Map.of("expression", "1 + 1");
            case CREATE_TODO -> Map.of("item", "完成健康计划");
        };
    }

    private void assertInvalidWith(AgentPlan plan, String expectedErrorFragment) {
        AgentPlanValidationResult result = validator.validate(plan);
        assertFalse(result.valid());
        assertContains(result, expectedErrorFragment);
    }

    private void assertContains(AgentPlanValidationResult result, String expectedErrorFragment) {
        assertTrue(
                result.errors().stream().anyMatch(error -> error.contains(expectedErrorFragment)),
                () -> "Expected an error containing '" + expectedErrorFragment + "' but got " + result.errors()
        );
    }
}
