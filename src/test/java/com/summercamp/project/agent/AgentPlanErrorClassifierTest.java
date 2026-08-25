package com.summercamp.project.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class AgentPlanErrorClassifierTest {
    @Test
    void mapsValidationMessagesToStableSafeCodes() {
        List<AgentPlanErrorCode> codes = AgentPlanErrorClassifier.classify(List.of(
                "Plan output is not valid JSON",
                "root contains an unsupported field",
                "steps[0].action is not a supported AgentAction",
                "Plan goal must exactly match the requested goal",
                "Step S2 requires non-blank input: location",
                "Plan must contain between 3 and 12 steps",
                "Plan must contain at least 3 distinct business task actions",
                "Duplicate step id: S1",
                "Step S2 has unknown dependency: S9",
                "Step S1 must not depend on itself",
                "Dependency graph must not contain a cycle",
                "Plan must contain exactly one VALIDATE step; found=0",
                "Plan must contain exactly one VALIDATE step; found=2",
                "Plan must contain exactly one SYNTHESIZE step; found=0",
                "Plan must contain exactly one SYNTHESIZE step; found=2",
                "SYNTHESIZE must be the last plan step",
                "SYNTHESIZE must directly depend on VALIDATE",
                "MISSING_REQUIRED_EXERCISE_ACTION",
                "MISSING_REQUIRED_MEAL_ACTION",
                "MISSING_REQUIRED_WEATHER_ACTION",
                "VALIDATE must close a business branch"
        ));

        assertEquals(List.of(
                AgentPlanErrorCode.JSON_PARSE_FAILED,
                AgentPlanErrorCode.UNKNOWN_FIELD,
                AgentPlanErrorCode.UNKNOWN_ACTION,
                AgentPlanErrorCode.GOAL_MISMATCH,
                AgentPlanErrorCode.INVALID_INPUTS,
                AgentPlanErrorCode.STEP_COUNT_INVALID,
                AgentPlanErrorCode.BUSINESS_TASK_COUNT_INVALID,
                AgentPlanErrorCode.DUPLICATE_STEP_ID,
                AgentPlanErrorCode.UNKNOWN_DEPENDENCY,
                AgentPlanErrorCode.SELF_DEPENDENCY,
                AgentPlanErrorCode.DEPENDENCY_CYCLE,
                AgentPlanErrorCode.VALIDATE_MISSING,
                AgentPlanErrorCode.VALIDATE_DUPLICATE,
                AgentPlanErrorCode.SYNTHESIZE_MISSING,
                AgentPlanErrorCode.SYNTHESIZE_DUPLICATE,
                AgentPlanErrorCode.SYNTHESIZE_NOT_LAST,
                AgentPlanErrorCode.SYNTHESIZE_VALIDATION_DEPENDENCY_INVALID,
                AgentPlanErrorCode.MISSING_REQUIRED_EXERCISE_ACTION,
                AgentPlanErrorCode.MISSING_REQUIRED_MEAL_ACTION,
                AgentPlanErrorCode.MISSING_REQUIRED_WEATHER_ACTION,
                AgentPlanErrorCode.OTHER_PLAN_VALIDATION_ERROR
        ), codes);
    }
}
