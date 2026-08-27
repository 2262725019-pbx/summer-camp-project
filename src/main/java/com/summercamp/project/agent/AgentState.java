package com.summercamp.project.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public final class AgentState implements AgentStateView {
    private final String goal;
    private final AgentPlan plan;
    private final Set<String> planStepIds;
    private final Map<String, AgentStepStatus> statusesByStepId = new LinkedHashMap<>();
    private final Map<String, AgentObservation> observationsByStepId = new LinkedHashMap<>();

    public AgentState(AgentPlan plan) {
        this.plan = Objects.requireNonNull(plan, "plan must not be null");
        this.goal = plan.goal();
        this.planStepIds = plan.steps().stream()
                .map(AgentStep::id)
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
        if (planStepIds.size() != plan.steps().size()) {
            throw new IllegalArgumentException("Plan step ids must be non-null and unique");
        }
        plan.steps().forEach(step -> statusesByStepId.put(step.id(), AgentStepStatus.PENDING));
    }

    public static AgentState restoreForResume(
            AgentStateSnapshot snapshot,
            String waitingStepId
    ) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        if (waitingStepId == null || waitingStepId.isBlank()) {
            throw new IllegalArgumentException("waitingStepId must not be blank");
        }
        AgentPlan plan = snapshot.plan();
        AgentStep waitingStep = plan.steps().stream()
                .filter(step -> waitingStepId.equals(step.id()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("waiting step is not in plan"));
        if (snapshot.statusOf(waitingStepId) != AgentStepStatus.FAILED
                || snapshot.findObservation(waitingStepId)
                .filter(AgentRunCheckpoint::needsUserInput)
                .isEmpty()) {
            throw new IllegalArgumentException("waiting step is not recoverable");
        }
        long waitingCount = snapshot.observations().stream()
                .filter(AgentRunCheckpoint::needsUserInput)
                .count();
        if (waitingCount != 1) {
            throw new IllegalArgumentException("checkpoint must contain exactly one waiting step");
        }
        if (snapshot.statuses().values().stream()
                .anyMatch(status -> status == AgentStepStatus.RUNNING
                        || status == AgentStepStatus.PENDING)) {
            throw new IllegalArgumentException("checkpoint contains non-terminal state");
        }

        AgentState restored = new AgentState(plan);
        for (AgentStep step : plan.steps()) {
            AgentStepStatus status = snapshot.statusOf(step.id());
            boolean reset = step.id().equals(waitingStep.id())
                    || (status == AgentStepStatus.SKIPPED
                    && AgentRunCheckpoint.dependsTransitivelyOn(step, waitingStepId, plan));
            if (reset) {
                continue;
            }
            AgentObservation observation = snapshot.findObservation(step.id())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "terminal checkpoint step has no observation: " + step.id()));
            restored.observationsByStepId.put(step.id(), observation);
            restored.statusesByStepId.put(step.id(), status);
        }
        return restored;
    }

    public String goal() {
        return goal;
    }

    public AgentPlan plan() {
        return plan;
    }

    public synchronized void recordObservation(AgentObservation observation) {
        Objects.requireNonNull(observation, "observation must not be null");
        if (!planStepIds.contains(observation.stepId())) {
            throw new IllegalArgumentException("Unknown step id: " + observation.stepId());
        }
        if (observationsByStepId.containsKey(observation.stepId())) {
            throw new IllegalStateException("Terminal observation already recorded for step: "
                    + observation.stepId());
        }
        AgentStepStatus currentStatus = statusesByStepId.get(observation.stepId());
        if (currentStatus != AgentStepStatus.PENDING && currentStatus != AgentStepStatus.RUNNING) {
            throw new IllegalStateException("Cannot record observation for step in status: " + currentStatus);
        }
        observationsByStepId.put(observation.stepId(), observation);
        statusesByStepId.put(
                observation.stepId(),
                observation.success() ? AgentStepStatus.COMPLETED : AgentStepStatus.FAILED
        );
    }

    synchronized void markRunning(String stepId) {
        requireStatus(stepId, AgentStepStatus.PENDING);
        statusesByStepId.put(stepId, AgentStepStatus.RUNNING);
    }

    synchronized void markSkipped(String stepId, String reason) {
        requireStatus(stepId, AgentStepStatus.PENDING);
        if (observationsByStepId.containsKey(stepId)) {
            throw new IllegalStateException("Terminal observation already recorded for step: " + stepId);
        }
        AgentObservation observation = new AgentObservation(stepId, false, reason);
        observationsByStepId.put(stepId, observation);
        statusesByStepId.put(stepId, AgentStepStatus.SKIPPED);
    }

    public synchronized Optional<AgentObservation> findObservation(String stepId) {
        return Optional.ofNullable(observationsByStepId.get(stepId));
    }

    public synchronized boolean isStepCompleted(String stepId) {
        return statusesByStepId.get(stepId) == AgentStepStatus.COMPLETED;
    }

    public synchronized AgentStepStatus statusOf(String stepId) {
        return findStatus(stepId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown step id: " + stepId));
    }

    public synchronized Map<String, AgentStepStatus> statuses() {
        return Map.copyOf(statusesByStepId);
    }

    public synchronized List<AgentStep> completedSteps() {
        return plan.steps().stream()
                .filter(step -> isStepCompleted(step.id()))
                .toList();
    }

    public synchronized List<AgentObservation> observations() {
        return List.copyOf(observationsByStepId.values());
    }

    synchronized Optional<AgentStepStatus> findStatus(String stepId) {
        return Optional.ofNullable(statusesByStepId.get(stepId));
    }

    synchronized boolean hasPendingSteps() {
        return statusesByStepId.containsValue(AgentStepStatus.PENDING);
    }

    private void requireStatus(String stepId, AgentStepStatus expected) {
        AgentStepStatus actual = statusesByStepId.get(stepId);
        if (actual == null) {
            throw new IllegalArgumentException("Unknown step id: " + stepId);
        }
        if (actual != expected) {
            throw new IllegalStateException(
                    "Step " + stepId + " must be " + expected + " but was " + actual
            );
        }
    }
}
