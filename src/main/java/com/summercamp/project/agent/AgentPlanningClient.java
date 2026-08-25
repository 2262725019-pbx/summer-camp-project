package com.summercamp.project.agent;

/**
 * Narrow LLM boundary used only for plan generation. Implementations must not
 * expose or execute tools, skills, or retrieval capabilities.
 */
@FunctionalInterface
public interface AgentPlanningClient {
    String generatePlan(String goal, String instructions);
}
