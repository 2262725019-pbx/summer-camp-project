package com.summercamp.project.agent.model;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class AgentRun {

    private final String id;
    private final String userId;
    private final HealthGoal goal;
    private final AgentPlan plan;
    private final Instant createdAt;
    private final Instant expiresAt;
    private final Map<String, MutableStepState> states = new LinkedHashMap<>();
    private final Map<String, Object> outputs = new LinkedHashMap<>();

    public AgentRun(
            String id,
            String userId,
            HealthGoal goal,
            AgentPlan plan,
            Instant createdAt,
            Instant expiresAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.goal = Objects.requireNonNull(goal, "goal");
        this.plan = Objects.requireNonNull(plan, "plan");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        for (AgentStep step : plan.steps()) {
            states.put(step.id(), new MutableStepState());
        }
    }

    public String id() {
        return id;
    }

    public String userId() {
        return userId;
    }

    public HealthGoal goal() {
        return goal;
    }

    public AgentPlan plan() {
        return plan;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public synchronized StepState state(String stepId) {
        MutableStepState state = requireState(stepId);
        return new StepState(state.status, state.attempts, state.errorType);
    }

    public synchronized Map<String, StepState> states() {
        Map<String, StepState> snapshot = new LinkedHashMap<>();
        states.forEach((id, state) -> snapshot.put(
                id, new StepState(state.status, state.attempts, state.errorType)));
        return Map.copyOf(snapshot);
    }

    public synchronized void start(String stepId) {
        MutableStepState state = requireState(stepId);
        state.status = StepStatus.RUNNING;
        state.attempts++;
        state.errorType = "";
    }

    public synchronized void succeed(String stepId, Object output) {
        MutableStepState state = requireState(stepId);
        state.status = StepStatus.SUCCEEDED;
        if (output != null) {
            outputs.put(stepId, output);
        }
    }

    public synchronized void fail(String stepId, Throwable error) {
        MutableStepState state = requireState(stepId);
        state.status = StepStatus.FAILED;
        state.errorType = error == null ? "Unknown" : error.getClass().getSimpleName();
    }

    public synchronized void skip(String stepId, String reason) {
        MutableStepState state = requireState(stepId);
        state.status = StepStatus.SKIPPED;
        state.errorType = reason == null ? "" : reason;
    }

    public synchronized boolean dependenciesSucceeded(AgentStep step) {
        return step.dependsOn().stream()
                .allMatch(id -> requireState(id).status == StepStatus.SUCCEEDED
                        || requireState(id).status == StepStatus.SKIPPED);
    }

    public synchronized Object output(String stepId) {
        return outputs.get(stepId);
    }

    private MutableStepState requireState(String stepId) {
        MutableStepState state = states.get(stepId);
        if (state == null) {
            throw new IllegalArgumentException("未知 Agent 步骤：" + stepId);
        }
        return state;
    }

    public record StepState(StepStatus status, int attempts, String errorType) {
    }

    private static final class MutableStepState {
        private StepStatus status = StepStatus.PENDING;
        private int attempts;
        private String errorType = "";
    }
}
