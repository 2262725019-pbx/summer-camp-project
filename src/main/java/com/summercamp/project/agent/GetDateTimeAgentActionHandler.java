package com.summercamp.project.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summercamp.project.tool.ToolRegistry;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public final class GetDateTimeAgentActionHandler extends AbstractToolAgentActionHandler {
    public GetDateTimeAgentActionHandler(ToolRegistry toolRegistry, ObjectMapper objectMapper) {
        super(
                AgentAction.GET_DATETIME,
                "get_current_datetime",
                List.of("timezone"),
                toolRegistry,
                objectMapper
        );
    }
}
