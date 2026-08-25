package com.summercamp.project.agent;

import java.util.List;

final class AgentPlanErrorClassifier {
    private AgentPlanErrorClassifier() {
    }

    static List<AgentPlanErrorCode> classify(List<String> errors) {
        if (errors == null) {
            return List.of(AgentPlanErrorCode.OTHER_PLAN_VALIDATION_ERROR);
        }
        return errors.stream().map(AgentPlanErrorClassifier::classify).toList();
    }

    static AgentPlanErrorCode classify(String error) {
        String message = error == null ? "" : error;
        if (message.contains("not valid JSON")
                || message.contains("non-blank JSON")
                || message.contains("JSON root must be an object")) {
            return AgentPlanErrorCode.JSON_PARSE_FAILED;
        }
        if (message.contains("unsupported field")) {
            return AgentPlanErrorCode.UNKNOWN_FIELD;
        }
        if (message.contains("supported AgentAction") || message.contains("must have an AgentAction")) {
            return AgentPlanErrorCode.UNKNOWN_ACTION;
        }
        if (message.contains("goal must exactly match") || message.contains("GOAL_MISMATCH")) {
            return AgentPlanErrorCode.GOAL_MISMATCH;
        }
        if (message.contains("input") || message.contains("inputs")) {
            return AgentPlanErrorCode.INVALID_INPUTS;
        }
        if (message.contains("between 3 and 12 steps")) {
            return AgentPlanErrorCode.STEP_COUNT_INVALID;
        }
        if (message.contains("distinct business task actions")) {
            return AgentPlanErrorCode.BUSINESS_TASK_COUNT_INVALID;
        }
        if (message.contains("Duplicate step id")) {
            return AgentPlanErrorCode.DUPLICATE_STEP_ID;
        }
        if (message.contains("unknown dependency")) {
            return AgentPlanErrorCode.UNKNOWN_DEPENDENCY;
        }
        if (message.contains("depend on itself")) {
            return AgentPlanErrorCode.SELF_DEPENDENCY;
        }
        if (message.contains("must not contain a cycle")) {
            return AgentPlanErrorCode.DEPENDENCY_CYCLE;
        }
        if (message.contains("exactly one VALIDATE") && message.contains("found=0")) {
            return AgentPlanErrorCode.VALIDATE_MISSING;
        }
        if (message.contains("exactly one VALIDATE")) {
            return AgentPlanErrorCode.VALIDATE_DUPLICATE;
        }
        if (message.contains("exactly one SYNTHESIZE") && message.contains("found=0")) {
            return AgentPlanErrorCode.SYNTHESIZE_MISSING;
        }
        if (message.contains("exactly one SYNTHESIZE")) {
            return AgentPlanErrorCode.SYNTHESIZE_DUPLICATE;
        }
        if (message.contains("SYNTHESIZE must be the last")) {
            return AgentPlanErrorCode.SYNTHESIZE_NOT_LAST;
        }
        if (message.contains("SYNTHESIZE must directly depend on VALIDATE")) {
            return AgentPlanErrorCode.SYNTHESIZE_VALIDATION_DEPENDENCY_INVALID;
        }
        if (message.contains(GoalCoverageValidator.MISSING_REQUIRED_EXERCISE_ACTION)) {
            return AgentPlanErrorCode.MISSING_REQUIRED_EXERCISE_ACTION;
        }
        if (message.contains(GoalCoverageValidator.MISSING_REQUIRED_MEAL_ACTION)) {
            return AgentPlanErrorCode.MISSING_REQUIRED_MEAL_ACTION;
        }
        if (message.contains(GoalCoverageValidator.MISSING_REQUIRED_WEATHER_ACTION)) {
            return AgentPlanErrorCode.MISSING_REQUIRED_WEATHER_ACTION;
        }
        return AgentPlanErrorCode.OTHER_PLAN_VALIDATION_ERROR;
    }
}
