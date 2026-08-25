package com.summercamp.project.agent;

import java.util.List;
import java.util.Objects;

public record AgentPlan(String goal, List<AgentStep> steps) {
    public AgentPlan {
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("goal must not be blank");
        }
        steps = List.copyOf(Objects.requireNonNull(steps, "steps must not be null"));
    }
}
