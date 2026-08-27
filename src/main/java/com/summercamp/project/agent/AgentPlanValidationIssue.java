package com.summercamp.project.agent;

import java.util.Objects;

record AgentPlanValidationIssue(
        AgentPlanValidationSource source,
        AgentPlanErrorCode code
) {
    AgentPlanValidationIssue {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(code, "code must not be null");
    }

    String safeLabel() {
        return source.name() + ":" + code.name();
    }
}

enum AgentPlanValidationSource {
    JSON_PARSER,
    PLAN_VALIDATOR,
    GOAL_COVERAGE_VALIDATOR
}
