package com.summercamp.project.agent;

@FunctionalInterface
public interface AgentPlanner {
    AgentPlan plan(String goal);
}
