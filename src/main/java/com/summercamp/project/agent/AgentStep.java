package com.summercamp.project.agent;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record AgentStep(
        String id,
        AgentAction action,
        String description,
        String reason,
        List<String> dependsOn,
        Map<String, String> inputs,
        AgentStepStatus status
) {
    public AgentStep {
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        inputs = Map.copyOf(Objects.requireNonNull(inputs, "inputs must not be null"));
        status = Objects.requireNonNull(status, "status must not be null");
    }

    public AgentStep(
            String id,
            AgentAction action,
            String description,
            String reason,
            List<String> dependsOn,
            AgentStepStatus status
    ) {
        this(id, action, description, reason, dependsOn, Map.of(), status);
    }

    public AgentStep(
            String id,
            AgentAction action,
            String description,
            String reason,
            List<String> dependsOn,
            Map<String, String> inputs
    ) {
        this(id, action, description, reason, dependsOn, inputs, AgentStepStatus.PENDING);
    }

    public AgentStep(
            String id,
            AgentAction action,
            String description,
            String reason,
            List<String> dependsOn
    ) {
        this(id, action, description, reason, dependsOn, Map.of(), AgentStepStatus.PENDING);
    }
}
