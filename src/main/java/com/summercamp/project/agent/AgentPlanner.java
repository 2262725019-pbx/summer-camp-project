package com.summercamp.project.agent;

@FunctionalInterface
public interface AgentPlanner {
    AgentPlan plan(String goal);

    default AgentPlan plan(String goal, AgentRunMetrics metrics) {
        return plan(goal);
    }
}
