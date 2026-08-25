package com.summercamp.project.agent;

import java.util.List;

public record AgentPlanValidationResult(boolean valid, List<String> errors) {
    public AgentPlanValidationResult {
        errors = errors == null ? List.of() : List.copyOf(errors);
        if (valid && !errors.isEmpty()) {
            throw new IllegalArgumentException("A valid result cannot contain errors");
        }
    }
}
