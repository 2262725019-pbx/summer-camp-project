package com.summercamp.project.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ValidateActionHandlerTest {
    private final ValidateActionHandler handler = new ValidateActionHandler();

    @Test
    void passesCompletedBusinessStepsIncludingRagMiss() {
        AgentPlan plan = plan();
        AgentState state = completedBusinessState(plan, false);

        AgentObservation result = handler.execute(plan.steps().get(3), context(plan, state));

        assertTrue(result.success());
        assertEquals(ValidateActionHandler.VALIDATION_PASSED, result.structuredData().get("code"));
    }

    @Test
    void rejectsIncompleteExecution() {
        AgentPlan plan = plan();
        AgentState state = new AgentState(plan);
        state.recordObservation(success("datetime"));

        AgentObservation result = handler.execute(plan.steps().get(3), context(plan, state));

        assertFalse(result.success());
        assertEquals(ValidateActionHandler.INCOMPLETE_EXECUTION, result.structuredData().get("code"));
    }

    @Test
    void reportsRecoverableInputRequirement() {
        AgentPlan plan = plan();
        AgentState state = new AgentState(plan);
        state.recordObservation(success("datetime"));
        state.recordObservation(new AgentObservation(
                "weather", false, "请补充所在城市", Map.of("code", "NEEDS_USER_INPUT", "recoverable", "true")));
        state.recordObservation(success("rag"));

        AgentObservation result = handler.execute(plan.steps().get(3), context(plan, state));

        assertFalse(result.success());
        assertEquals(ValidateActionHandler.NEEDS_USER_INPUT, result.structuredData().get("code"));
        assertEquals("true", result.structuredData().get("recoverable"));
    }

    @Test
    void rejectsFailedUpstreamAsSuccessfulValidation() {
        AgentPlan plan = plan();
        AgentState state = new AgentState(plan);
        state.recordObservation(success("datetime"));
        state.recordObservation(new AgentObservation("weather", false, "天气能力失败"));
        state.recordObservation(success("rag"));

        AgentObservation result = handler.execute(plan.steps().get(3), context(plan, state));

        assertFalse(result.success());
        assertEquals(ValidateActionHandler.UPSTREAM_STEP_FAILED, result.structuredData().get("code"));
    }

    @Test
    void rejectsMissingSuccessfulResultsForEveryExplicitRequiredDomain() {
        assertMissingRequiredResult(
                "制定运动计划",
                List.of(AgentAction.GET_DATETIME, AgentAction.GET_WEATHER, AgentAction.RETRIEVE_KNOWLEDGE),
                ValidateActionHandler.MISSING_REQUIRED_EXERCISE_RESULT
        );
        assertMissingRequiredResult(
                "制定饮食计划",
                List.of(AgentAction.GET_DATETIME, AgentAction.GET_WEATHER, AgentAction.RETRIEVE_KNOWLEDGE),
                ValidateActionHandler.MISSING_REQUIRED_MEAL_RESULT
        );
        assertMissingRequiredResult(
                "根据天气制定健康方案",
                List.of(AgentAction.GET_DATETIME, AgentAction.RETRIEVE_KNOWLEDGE, AgentAction.CALCULATE),
                ValidateActionHandler.MISSING_REQUIRED_WEATHER_RESULT
        );
    }

    @Test
    void temporalGoalRequiresSuccessfulDatetimeObservation() {
        AgentPlan plan = coveragePlan(
                "未来7天健康生活规划",
                List.of(
                        AgentAction.GET_DATETIME,
                        AgentAction.RETRIEVE_KNOWLEDGE,
                        AgentAction.CALCULATE
                )
        );
        AgentState failedState = new AgentState(plan);
        failedState.recordObservation(new AgentObservation("B1", false, "日期工具失败"));
        failedState.recordObservation(success("B2"));
        failedState.recordObservation(success("B3"));
        AgentStep validation = plan.steps().get(plan.steps().size() - 2);

        AgentObservation failed = handler.execute(validation, context(plan, failedState));

        assertFalse(failed.success());
        assertEquals(
                ValidateActionHandler.MISSING_REQUIRED_DATETIME_RESULT,
                failed.structuredData().get("code")
        );

        AgentState successfulState = new AgentState(plan);
        successfulState.recordObservation(success("B1"));
        successfulState.recordObservation(success("B2"));
        successfulState.recordObservation(success("B3"));

        AgentObservation passed = handler.execute(validation, context(plan, successfulState));

        assertTrue(passed.success());
        assertEquals(ValidateActionHandler.VALIDATION_PASSED,
                passed.structuredData().get("code"));
    }

    private AgentState completedBusinessState(AgentPlan plan, boolean ragMatched) {
        AgentState state = new AgentState(plan);
        state.recordObservation(success("datetime"));
        state.recordObservation(success("weather"));
        state.recordObservation(new AgentObservation(
                "rag", true, "本地知识检索完成", Map.of("matched", Boolean.toString(ragMatched))));
        return state;
    }

    private AgentObservation success(String id) {
        return new AgentObservation(id, true, id + " completed");
    }

    private void assertMissingRequiredResult(
            String goal,
            List<AgentAction> businessActions,
            String expectedCode
    ) {
        AgentPlan plan = coveragePlan(goal, businessActions);
        AgentState state = new AgentState(plan);
        plan.steps().stream()
                .filter(candidate -> candidate.action() != AgentAction.VALIDATE
                        && candidate.action() != AgentAction.SYNTHESIZE)
                .forEach(candidate -> state.recordObservation(success(candidate.id())));

        AgentStep validation = plan.steps().get(plan.steps().size() - 2);
        AgentObservation result = handler.execute(validation, context(plan, state));

        assertFalse(result.success());
        assertEquals(expectedCode, result.structuredData().get("code"));
    }

    private AgentPlan coveragePlan(String goal, List<AgentAction> businessActions) {
        java.util.ArrayList<AgentStep> steps = new java.util.ArrayList<>();
        java.util.ArrayList<String> dependencies = new java.util.ArrayList<>();
        for (int index = 0; index < businessActions.size(); index++) {
            String id = "B" + (index + 1);
            AgentAction action = businessActions.get(index);
            steps.add(step(id, action, List.of(), validInputs(action)));
            dependencies.add(id);
        }
        steps.add(step("validate", AgentAction.VALIDATE, dependencies, Map.of()));
        steps.add(step("synthesis", AgentAction.SYNTHESIZE, List.of("validate"), Map.of()));
        return new AgentPlan(goal, steps);
    }

    private Map<String, String> validInputs(AgentAction action) {
        return switch (action) {
            case GET_DATETIME -> Map.of();
            case GET_WEATHER -> Map.of("location", "镇江", "period", "THREE_DAYS");
            case RETRIEVE_KNOWLEDGE -> Map.of("query", "健康生活");
            case CALCULATE -> Map.of("expression", "4 * 40");
            default -> Map.of();
        };
    }

    private AgentExecutionContext context(AgentPlan plan, AgentState state) {
        return new AgentExecutionContext(plan.goal(), state, plan);
    }

    private AgentPlan plan() {
        return new AgentPlan("制定今天的健康生活计划", List.of(
                step("datetime", AgentAction.GET_DATETIME, List.of(), Map.of()),
                step("weather", AgentAction.GET_WEATHER, List.of("datetime"),
                        Map.of("location", "镇江", "period", "TODAY")),
                step("rag", AgentAction.RETRIEVE_KNOWLEDGE, List.of(), Map.of("query", "健康生活")),
                step("validate", AgentAction.VALIDATE, List.of("weather", "rag"), Map.of()),
                step("synthesis", AgentAction.SYNTHESIZE, List.of("validate"), Map.of())
        ));
    }

    private AgentStep step(
            String id,
            AgentAction action,
            List<String> dependencies,
            Map<String, String> inputs
    ) {
        return new AgentStep(id, action, "执行 " + id, "满足用户目标", dependencies, inputs);
    }
}
