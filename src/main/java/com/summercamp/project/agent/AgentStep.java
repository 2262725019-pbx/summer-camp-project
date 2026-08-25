package com.summercamp.project.agent;

import java.util.List;
import java.util.Objects;

public record AgentStep(
        String id,
        AgentAction action,
        String description,
        String reason,
        List<String> dependsOn,
        AgentStepStatus status
) {
    public AgentStep {
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        status = Objects.requireNonNull(status, "status must not be null");
    }

    public AgentStep(
            String id,
            AgentAction action,
            String description,
            String reason,
            List<String> dependsOn
    ) {
        this(id, action, description, reason, dependsOn, AgentStepStatus.PENDING);
    }
}
