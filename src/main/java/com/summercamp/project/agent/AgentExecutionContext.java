package com.summercamp.project.agent;

import java.util.Objects;

public final class AgentExecutionContext {
    private final String originalGoal;
    private final AgentState state;
    private final AgentPlan plan;

    public AgentExecutionContext(String originalGoal, AgentState state, AgentPlan plan) {
        if (originalGoal == null || originalGoal.isBlank()) {
            throw new IllegalArgumentException("originalGoal must not be blank");
        }
        this.originalGoal = originalGoal;
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.plan = Objects.requireNonNull(plan, "plan must not be null");
        if (state.plan() != plan) {
            throw new IllegalArgumentException("state and context must reference the same plan");
        }
    }

    public String originalGoal() {
        return originalGoal;
    }

    public AgentStateView state() {
        return state;
    }

    public AgentPlan plan() {
        return plan;
    }

    AgentState mutableState() {
        return state;
    }
}
