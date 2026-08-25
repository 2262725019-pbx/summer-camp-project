package com.summercamp.project.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summercamp.project.tool.ToolRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    @Override
    protected Map<String, String> observationData(AgentStep step, String content) {
        Map<String, String> data = new LinkedHashMap<>(super.observationData(step, content));
        data.put("location", step.inputs().get("location"));
        data.put("period", step.inputs().get("period"));
        return Map.copyOf(data);
    }
}
