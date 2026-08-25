package com.summercamp.project.agent;

import java.util.Objects;

public record AgentRunResult(
        Status status,
        String reply,
        AgentPlan plan,
        AgentStateView state
) {
    public enum Status {
        COMPLETED,
        NEEDS_USER_INPUT,
        FAILED
    }

    public AgentRunResult {
        status = Objects.requireNonNull(status, "status must not be null");
        reply = reply == null ? "" : reply;
        if (state != null && !(state instanceof AgentStateSnapshot)) {
            state = AgentStateSnapshot.from(state);
        }
    }
}
