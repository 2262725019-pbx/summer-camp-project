package com.summercamp.project.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class AgentActionHandlerRegistryTest {
    @Test
    void findsRegisteredHandler() {
        FakeAgentActionHandler handler = handler(AgentAction.GET_WEATHER);
        AgentActionHandlerRegistry registry = new AgentActionHandlerRegistry(List.of(handler));

        assertSame(handler, registry.find(AgentAction.GET_WEATHER));
    }

    @Test
    void missingHandlerFailsClearly() {
        AgentActionHandlerRegistry registry = new AgentActionHandlerRegistry(List.of());

        AgentActionHandlerNotFoundException exception = assertThrows(
                AgentActionHandlerNotFoundException.class,
                () -> registry.find(AgentAction.GET_WEATHER)
        );

        assertTrue(exception.getMessage().contains("GET_WEATHER"));
    }

    @Test
    void duplicateActionFailsRegistryConstruction() {
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> new AgentActionHandlerRegistry(List.of(
                        handler(AgentAction.CALCULATE),
                        handler(AgentAction.CALCULATE)
                ))
        );

        assertEquals("Duplicate AgentActionHandler for action: CALCULATE", exception.getMessage());
    }

    private FakeAgentActionHandler handler(AgentAction action) {
        return new FakeAgentActionHandler(
                action,
                (step, context) -> new AgentObservation(step.id(), true, "ok")
        );
    }
}
