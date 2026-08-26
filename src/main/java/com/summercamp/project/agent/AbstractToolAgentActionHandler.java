package com.summercamp.project.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.summercamp.project.tool.ToolContext;
import com.summercamp.project.tool.ToolRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

abstract class AbstractToolAgentActionHandler implements AgentActionHandler {
    private static final int MAX_OBSERVATION_CONTENT_CHARACTERS = 4_000;

    private final AgentAction action;
    private final String toolName;
    private final List<String> argumentNames;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final AgentActionInputValidator inputValidator = new AgentActionInputValidator();

    AbstractToolAgentActionHandler(
            AgentAction action,
            String toolName,
            List<String> argumentNames,
            ToolRegistry toolRegistry,
            ObjectMapper objectMapper
    ) {
        this.action = Objects.requireNonNull(action, "action must not be null");
        this.toolName = Objects.requireNonNull(toolName, "toolName must not be null");
        this.argumentNames = List.copyOf(argumentNames);
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public final AgentAction action() {
        return action;
    }

    @Override
    public final AgentObservation execute(AgentStep step, AgentExecutionContext context) {
        if (step.action() != action) {
            return invalidInput(step, "Handler action does not match step action");
        }
        List<String> errors = inputValidator.validate(step);
        if (!errors.isEmpty()) {
            return invalidInput(step, String.join("; ", errors));
        }

        ObjectNode arguments = objectMapper.createObjectNode();
        for (String argumentName : argumentNames) {
            String value = step.inputs().get(argumentName);
            if (value != null) {
                arguments.put(argumentName, value);
            }
        }

        String argumentJson;
        try {
            argumentJson = objectMapper.writeValueAsString(arguments);
        } catch (JsonProcessingException exception) {
            return new AgentObservation(
                    step.id(),
                    false,
                    "Could not serialize tool arguments",
                    Map.of("code", "ARGUMENT_SERIALIZATION_FAILED", "tool", toolName)
            );
        }

        ToolContext toolContext = new ToolContext(
                context.userId(),
                context.originalGoal(),
                context.history(),
                context.metrics()
        );
        ToolRegistry.Invocation invocation = toolRegistry.invoke(toolName, argumentJson, toolContext);
        String content = safeContent(invocation == null ? null : invocation.modelContent());
        if (invocation == null || !invocation.success()) {
            Map<String, String> data = new LinkedHashMap<>(observationData(step, content));
            data.put("code", "TOOL_EXECUTION_FAILED");
            return new AgentObservation(step.id(), false, failureSummary(content), data);
        }
        return new AgentObservation(
                step.id(),
                true,
                "Tool " + toolName + " completed successfully",
                observationData(step, content)
        );
    }

    protected Map<String, String> observationData(AgentStep step, String content) {
        return Map.of("tool", toolName, "modelContent", content);
    }

    private AgentObservation invalidInput(AgentStep step, String summary) {
        return new AgentObservation(
                step.id(),
                false,
                summary,
                Map.of("code", "INVALID_INPUT", "tool", toolName)
        );
    }

    private String failureSummary(String content) {
        return content.isBlank() ? "Tool " + toolName + " failed" : content;
    }

    private String safeContent(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String normalized = content.strip();
        return normalized.length() <= MAX_OBSERVATION_CONTENT_CHARACTERS
                ? normalized
                : normalized.substring(0, MAX_OBSERVATION_CONTENT_CHARACTERS) + "…";
    }
}
