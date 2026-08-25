package com.summercamp.project.agent;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface AgentStateView {
    String goal();

    AgentPlan plan();

    Optional<AgentObservation> findObservation(String stepId);

    boolean isStepCompleted(String stepId);

    AgentStepStatus statusOf(String stepId);

    Map<String, AgentStepStatus> statuses();

    List<AgentStep> completedSteps();

    List<AgentObservation> observations();
}
