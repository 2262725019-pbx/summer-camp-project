package com.summercamp.project.agent;

@FunctionalInterface
public interface AgentSynthesisClient {
    String synthesize(String originalGoal, String observationContext);
}
