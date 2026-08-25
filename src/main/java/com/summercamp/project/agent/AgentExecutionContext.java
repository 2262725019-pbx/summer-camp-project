package com.summercamp.project.agent;

import com.summercamp.project.llm.ChatMessage;
import java.util.List;
import java.util.Objects;

public final class AgentExecutionContext {
    private final String userId;
    private final String originalGoal;
    private final List<ChatMessage> history;
    private final boolean voiceMessage;
    private final AgentState state;
    private final AgentPlan plan;

    public AgentExecutionContext(String originalGoal, AgentState state, AgentPlan plan) {
        this("", originalGoal, List.of(), false, state, plan);
    }

    public AgentExecutionContext(
            String userId,
            String originalGoal,
            List<ChatMessage> history,
            boolean voiceMessage,
            AgentState state,
            AgentPlan plan
    ) {
        if (originalGoal == null || originalGoal.isBlank()) {
            throw new IllegalArgumentException("originalGoal must not be blank");
        }
        this.userId = userId == null ? "" : userId;
        this.originalGoal = originalGoal;
        this.history = history == null ? List.of() : List.copyOf(history);
        this.voiceMessage = voiceMessage;
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.plan = Objects.requireNonNull(plan, "plan must not be null");
        if (state.plan() != plan) {
            throw new IllegalArgumentException("state and context must reference the same plan");
        }
    }

    public String userId() {
        return userId;
    }

    public String originalGoal() {
        return originalGoal;
    }

    public List<ChatMessage> history() {
        return history;
    }

    public boolean voiceMessage() {
        return voiceMessage;
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
