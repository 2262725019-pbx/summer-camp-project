package com.summercamp.project.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentPlanClosureNormalizerTest {
    private final AgentPlanClosureNormalizer normalizer = new AgentPlanClosureNormalizer();

    @Test
    void normalizesOnlyValidateAndSynthesisClosureWithoutMutatingBusinessSteps() {
        AgentStep datetime = step("date", AgentAction.GET_DATETIME, List.of(), Map.of());
        AgentStep weather = step("weather", AgentAction.GET_WEATHER, List.of("date"),
                Map.of("location", "镇江", "period", "THREE_DAYS"));
        AgentStep exercise = step("exercise", AgentAction.RUN_EXERCISE_SKILL,
                List.of("weather"), Map.of("request", "运动计划"));
        AgentStep synthesis = step("synthesis", AgentAction.SYNTHESIZE,
                List.of("exercise"), Map.of());
        AgentStep validation = step("validate", AgentAction.VALIDATE,
                List.of("weather"), Map.of());
        AgentPlan original = new AgentPlan("未来7天结合镇江市天气安排运动", List.of(
                datetime, weather, exercise, synthesis, validation));
        List<AgentPlanValidationIssue> closureIssues = List.of(
                issue(AgentPlanErrorCode.SYNTHESIZE_NOT_LAST),
                issue(AgentPlanErrorCode.SYNTHESIZE_VALIDATION_DEPENDENCY_INVALID),
                issue(AgentPlanErrorCode.VALIDATE_BRANCH_COVERAGE_INVALID));

        AgentPlan normalized = normalizer.normalize(original, closureIssues).orElseThrow();

        assertEquals(List.of(datetime, weather, exercise), normalized.steps().subList(0, 3));
        assertEquals(AgentAction.VALIDATE, normalized.steps().get(3).action());
        assertEquals(List.of("date", "weather", "exercise"),
                normalized.steps().get(3).dependsOn());
        assertEquals(AgentAction.SYNTHESIZE, normalized.steps().getLast().action());
        assertEquals(List.of("validate"), normalized.steps().getLast().dependsOn());
        assertTrue(new AgentPlanValidator().validate(normalized).valid());
    }

    @Test
    void refusesBusinessCycleUnknownActionAndInvalidInputs() {
        AgentPlan plan = new AgentPlan("健康计划", List.of(
                step("a", AgentAction.GET_DATETIME, List.of(), Map.of()),
                step("b", AgentAction.RETRIEVE_KNOWLEDGE, List.of(), Map.of("query", "健康")),
                step("c", AgentAction.CALCULATE, List.of(), Map.of("expression", "1+1")),
                step("v", AgentAction.VALIDATE, List.of("a", "b", "c"), Map.of()),
                step("s", AgentAction.SYNTHESIZE, List.of("v"), Map.of())));

        for (AgentPlanErrorCode code : List.of(
                AgentPlanErrorCode.DEPENDENCY_CYCLE,
                AgentPlanErrorCode.UNKNOWN_ACTION,
                AgentPlanErrorCode.INVALID_INPUTS)) {
            assertFalse(normalizer.normalize(plan, List.of(issue(code))).isPresent());
        }
    }

    private AgentPlanValidationIssue issue(AgentPlanErrorCode code) {
        return new AgentPlanValidationIssue(AgentPlanValidationSource.PLAN_VALIDATOR, code);
    }

    private AgentStep step(
            String id,
            AgentAction action,
            List<String> dependencies,
            Map<String, String> inputs
    ) {
        return new AgentStep(id, action, "执行", "原因", dependencies, inputs);
    }
}
