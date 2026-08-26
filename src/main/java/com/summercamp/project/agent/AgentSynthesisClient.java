package com.summercamp.project.agent;

@FunctionalInterface
public interface AgentSynthesisClient {
    String synthesize(String originalGoal, String observationContext);

    default String synthesize(
            String originalGoal,
            String observationContext,
            AgentRunMetrics metrics
    ) {
        return synthesize(originalGoal, observationContext);
    }
}
