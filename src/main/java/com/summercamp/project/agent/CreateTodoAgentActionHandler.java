package com.summercamp.project.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summercamp.project.tool.ToolRegistry;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public final class CreateTodoAgentActionHandler extends AbstractToolAgentActionHandler {
    public CreateTodoAgentActionHandler(ToolRegistry toolRegistry, ObjectMapper objectMapper) {
        super(AgentAction.CREATE_TODO, "add_todo", List.of("item"), toolRegistry, objectMapper);
    }
}
