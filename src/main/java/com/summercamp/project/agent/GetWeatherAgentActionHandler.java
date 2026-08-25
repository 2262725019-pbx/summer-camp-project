package com.summercamp.project.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summercamp.project.tool.ToolRegistry;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public final class GetWeatherAgentActionHandler extends AbstractToolAgentActionHandler {
    public GetWeatherAgentActionHandler(ToolRegistry toolRegistry, ObjectMapper objectMapper) {
        super(
                AgentAction.GET_WEATHER,
                "get_weather",
                List.of("location", "period"),
                toolRegistry,
                objectMapper
        );
    }
}
