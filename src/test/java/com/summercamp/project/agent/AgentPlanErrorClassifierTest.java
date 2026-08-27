package com.summercamp.project.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class AgentPlanErrorClassifierTest {
    @Test
    void mapsValidationMessagesToStableSafeCodes() {
        List<AgentPlanErrorCode> codes = AgentPlanErrorClassifier.classify(List.of(
                "Plan output is not valid JSON",
                "root.steps must be an array",
                "root contains an unsupported field",
                "steps[0].action is not a supported AgentAction",
                "Plan goal must exactly match the requested goal",
                "Plan must not be null",
                "Goal must not be blank",
                "Step at index 0 must not be null",
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
                "At least one business step must execute before VALIDATE",
                "Business step S4 must not follow VALIDATE",
                "VALIDATE must close business branch ending at step S4",
                "MISSING_REQUIRED_DATETIME_ACTION",
                "MISSING_REQUIRED_EXERCISE_ACTION",
                "MISSING_REQUIRED_MEAL_ACTION",
                "MISSING_REQUIRED_WEATHER_ACTION",
                "VALIDATE must close a business branch"
        ));

        assertEquals(List.of(
                AgentPlanErrorCode.JSON_PARSE_FAILED,
                AgentPlanErrorCode.JSON_STRUCTURE_INVALID,
                AgentPlanErrorCode.UNKNOWN_FIELD,
                AgentPlanErrorCode.UNKNOWN_ACTION,
                AgentPlanErrorCode.GOAL_MISMATCH,
                AgentPlanErrorCode.PLAN_INVALID,
                AgentPlanErrorCode.GOAL_INVALID,
                AgentPlanErrorCode.STEP_INVALID,
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
                AgentPlanErrorCode.BUSINESS_STEP_MISSING,
                AgentPlanErrorCode.BUSINESS_STEP_ORDER_INVALID,
                AgentPlanErrorCode.VALIDATE_BRANCH_COVERAGE_INVALID,
                AgentPlanErrorCode.MISSING_REQUIRED_DATETIME_ACTION,
                AgentPlanErrorCode.MISSING_REQUIRED_EXERCISE_ACTION,
                AgentPlanErrorCode.MISSING_REQUIRED_MEAL_ACTION,
                AgentPlanErrorCode.MISSING_REQUIRED_WEATHER_ACTION,
                AgentPlanErrorCode.OTHER_PLAN_VALIDATION_ERROR
        ), codes);
    }

    @Test
    void typedIssueKeepsSafeValidationSourceWithoutRawPlanData() {
        AgentPlanValidationIssue issue = new AgentPlanValidationIssue(
                AgentPlanValidationSource.PLAN_VALIDATOR,
                AgentPlanErrorCode.VALIDATE_BRANCH_COVERAGE_INVALID
        );

        assertEquals(
                "PLAN_VALIDATOR:VALIDATE_BRANCH_COVERAGE_INVALID",
                issue.safeLabel()
        );
    }
}
