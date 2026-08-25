package com.summercamp.project.agent;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public final class AgentActionHandlerRegistry {
    private final Map<AgentAction, AgentActionHandler> handlersByAction;

    public AgentActionHandlerRegistry(List<AgentActionHandler> handlers) {
        Objects.requireNonNull(handlers, "handlers must not be null");
        Map<AgentAction, AgentActionHandler> registered = new EnumMap<>(AgentAction.class);
        for (AgentActionHandler handler : handlers) {
            Objects.requireNonNull(handler, "handler must not be null");
            AgentAction action = Objects.requireNonNull(handler.action(), "handler action must not be null");
            AgentActionHandler existing = registered.putIfAbsent(action, handler);
            if (existing != null) {
                throw new IllegalStateException("Duplicate AgentActionHandler for action: " + action);
            }
        }
        this.handlersByAction = Map.copyOf(registered);
    }

    public AgentActionHandler find(AgentAction action) {
        Objects.requireNonNull(action, "action must not be null");
        AgentActionHandler handler = handlersByAction.get(action);
        if (handler == null) {
            throw new AgentActionHandlerNotFoundException(action);
        }
        return handler;
    }
}
