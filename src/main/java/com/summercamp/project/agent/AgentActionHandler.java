package com.summercamp.project.agent;

public interface AgentActionHandler {
    AgentAction action();

    AgentObservation execute(AgentStep step, AgentExecutionContext context);
}
