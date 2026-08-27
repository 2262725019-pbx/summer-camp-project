package com.summercamp.project.agent;

@FunctionalInterface
public interface AgentSynthesisClient {
    AgentSynthesisResult synthesize(String originalGoal, String observationContext);

    default AgentSynthesisResult synthesize(
            String originalGoal,
            String observationContext,
            AgentRunMetrics metrics
    ) {
        return synthesize(originalGoal, observationContext);
    }
}
