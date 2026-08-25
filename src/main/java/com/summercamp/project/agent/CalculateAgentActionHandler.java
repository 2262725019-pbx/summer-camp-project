package com.summercamp.project.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summercamp.project.tool.ToolRegistry;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public final class CalculateAgentActionHandler extends AbstractToolAgentActionHandler {
    public CalculateAgentActionHandler(ToolRegistry toolRegistry, ObjectMapper objectMapper) {
        super(AgentAction.CALCULATE, "calculate", List.of("expression"), toolRegistry, objectMapper);
    }
}
