package com.summercamp.project.agent;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class AgentStateSnapshot implements AgentStateView {
    private final String goal;
    private final AgentPlan plan;
    private final Map<String, AgentStepStatus> statuses;
    private final Map<String, AgentObservation> observationsByStepId;
    private final List<AgentObservation> observations;

    private AgentStateSnapshot(AgentStateView source) {
        goal = source.goal();
        plan = source.plan();
        statuses = Map.copyOf(source.statuses());
        observations = List.copyOf(source.observations());
        observationsByStepId = observations.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                AgentObservation::stepId,
                observation -> observation
        ));
    }

    public static AgentStateSnapshot from(AgentStateView source) {
        return new AgentStateSnapshot(Objects.requireNonNull(source, "source must not be null"));
    }

    @Override
    public String goal() {
        return goal;
    }

    @Override
    public AgentPlan plan() {
        return plan;
    }

    @Override
    public Optional<AgentObservation> findObservation(String stepId) {
        return Optional.ofNullable(observationsByStepId.get(stepId));
    }

    @Override
    public boolean isStepCompleted(String stepId) {
        return statusOf(stepId) == AgentStepStatus.COMPLETED;
    }

    @Override
    public AgentStepStatus statusOf(String stepId) {
        AgentStepStatus status = statuses.get(stepId);
        if (status == null) {
            throw new IllegalArgumentException("Unknown step id: " + stepId);
        }
        return status;
    }

    @Override
    public Map<String, AgentStepStatus> statuses() {
        return statuses;
    }

    @Override
    public List<AgentStep> completedSteps() {
        return plan.steps().stream().filter(step -> isStepCompleted(step.id())).toList();
    }

    @Override
    public List<AgentObservation> observations() {
        return observations;
    }
}
