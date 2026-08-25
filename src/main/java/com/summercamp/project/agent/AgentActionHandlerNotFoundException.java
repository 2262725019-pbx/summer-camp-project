package com.summercamp.project.agent;

public final class AgentActionHandlerNotFoundException extends IllegalStateException {
    public AgentActionHandlerNotFoundException(AgentAction action) {
        super("No AgentActionHandler registered for action: " + action);
    }
}
