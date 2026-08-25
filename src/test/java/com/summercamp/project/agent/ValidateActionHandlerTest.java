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
