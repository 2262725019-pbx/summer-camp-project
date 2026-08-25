package com.summercamp.project.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public final class AgentState {
    private final String goal;
    private final AgentPlan plan;
    private final Set<String> planStepIds;
    private final Map<String, AgentObservation> observationsByStepId = new LinkedHashMap<>();

    public AgentState(AgentPlan plan) {
        this.plan = Objects.requireNonNull(plan, "plan must not be null");
        this.goal = plan.goal();
        this.planStepIds = plan.steps().stream()
                .map(AgentStep::id)
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
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
        observationsByStepId.put(observation.stepId(), observation);
    }

    public synchronized Optional<AgentObservation> findObservation(String stepId) {
        return Optional.ofNullable(observationsByStepId.get(stepId));
    }

    public synchronized boolean isStepCompleted(String stepId) {
        AgentObservation observation = observationsByStepId.get(stepId);
        return observation != null && observation.success();
    }

    public synchronized List<AgentStep> completedSteps() {
        return plan.steps().stream()
                .filter(step -> isStepCompleted(step.id()))
                .toList();
    }

    public synchronized List<AgentObservation> observations() {
        return List.copyOf(observationsByStepId.values());
    }
}
