package com.summercamp.project.agent.model;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import com.summercamp.project.agent.execution.AgentCancelledException;

public final class AgentRun {

    private final String id;
    private final String userId;
    private final HealthGoal goal;
    private final AgentPlan plan;
    private final Instant createdAt;
    private final Instant expiresAt;
    private final Map<String, MutableStepState> states = new LinkedHashMap<>();
    private final Map<String, Object> outputs = new LinkedHashMap<>();
    private Runnable changeListener = () -> { };
    private volatile boolean cancelled;

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

    public synchronized Map<String, Object> outputs() {
        return Map.copyOf(outputs);
    }

    public synchronized void attachChangeListener(Runnable listener) {
        changeListener = listener == null ? () -> { } : listener;
    }

    public synchronized void restore(
            Map<String, StepState> restoredStates,
            Map<String, Object> restoredOutputs) {
        restoredStates.forEach((stepId, restored) -> {
            MutableStepState state = requireState(stepId);
            state.status = Objects.requireNonNull(restored.status(), "status");
            state.attempts = Math.max(0, restored.attempts());
            state.errorType = restored.errorType() == null ? "" : restored.errorType();
        });
        outputs.clear();
        restoredOutputs.forEach((stepId, output) -> {
            requireState(stepId);
            if (output != null) {
                outputs.put(stepId, output);
            }
        });
    }

    public synchronized void start(String stepId) {
        ensureActive();
        MutableStepState state = requireState(stepId);
        state.status = StepStatus.RUNNING;
        state.attempts++;
        state.errorType = "";
        changed();
    }

    public synchronized void succeed(String stepId, Object output) {
        ensureActive();
        MutableStepState state = requireState(stepId);
        state.status = StepStatus.SUCCEEDED;
        if (output != null) {
            outputs.put(stepId, output);
        }
        changed();
    }

    public synchronized void fail(String stepId, Throwable error) {
        ensureActive();
        MutableStepState state = requireState(stepId);
        state.status = StepStatus.FAILED;
        state.errorType = error == null ? "Unknown" : error.getClass().getSimpleName();
        changed();
    }

    public synchronized void skip(String stepId, String reason) {
        ensureActive();
        MutableStepState state = requireState(stepId);
        state.status = StepStatus.SKIPPED;
        state.errorType = reason == null ? "" : reason;
        changed();
    }

    public synchronized boolean dependenciesSucceeded(AgentStep step) {
        return step.dependsOn().stream()
                .allMatch(id -> requireState(id).status == StepStatus.SUCCEEDED
                        || requireState(id).status == StepStatus.SKIPPED);
    }

    public synchronized Object output(String stepId) {
        return outputs.get(stepId);
    }

    public synchronized void prepareForResume() {
        boolean reset = false;
        for (Map.Entry<String, MutableStepState> entry : states.entrySet()) {
            String stepId = entry.getKey();
            MutableStepState state = entry.getValue();
            if (state.status == StepStatus.FAILED || state.status == StepStatus.RUNNING) {
                state.status = StepStatus.PENDING;
                state.attempts = 0;
                state.errorType = "";
                outputs.remove(stepId);
                reset = true;
            }
        }
        if (reset) {
            changed();
        }
    }

    public void cancel() {
        cancelled = true;
    }

    public boolean cancelled() {
        return cancelled;
    }

    public void ensureActive() {
        if (cancelled) {
            throw new AgentCancelledException();
        }
    }

    public synchronized boolean resumable() {
        return states.values().stream().anyMatch(state -> state.status == StepStatus.FAILED
                || state.status == StepStatus.RUNNING || state.status == StepStatus.PENDING);
    }

    private MutableStepState requireState(String stepId) {
        MutableStepState state = states.get(stepId);
        if (state == null) {
            throw new IllegalArgumentException("未知 Agent 步骤：" + stepId);
        }
        return state;
    }

    private void changed() {
        changeListener.run();
    }

    public record StepState(StepStatus status, int attempts, String errorType) {
    }

    private static final class MutableStepState {
        private StepStatus status = StepStatus.PENDING;
        private int attempts;
        private String errorType = "";
    }
}
