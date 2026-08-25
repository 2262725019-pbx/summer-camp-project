package com.summercamp.project.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

final class AgentPlanJsonParser {
    private static final Set<String> ROOT_FIELDS = Set.of("goal", "steps");
    private static final Set<String> STEP_FIELDS = Set.of(
            "id", "action", "description", "reason", "dependsOn");

    private final ObjectMapper objectMapper;

    AgentPlanJsonParser(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    AgentPlan parse(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            throw new AgentPlanParseException("Plan output must be non-blank JSON");
        }

        JsonNode root;
        try {
            root = objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(rawJson);
        } catch (JsonProcessingException exception) {
            throw new AgentPlanParseException("Plan output is not valid JSON", exception);
        }
        if (!root.isObject()) {
            throw new AgentPlanParseException("Plan JSON root must be an object");
        }

        ObjectNode rootObject = (ObjectNode) root;
        requireOnlyFields(rootObject, ROOT_FIELDS, "root");
        String goal = requiredNonBlankText(rootObject, "goal", "root");
        JsonNode stepsNode = rootObject.get("steps");
        if (stepsNode == null || !stepsNode.isArray()) {
            throw new AgentPlanParseException("root.steps must be an array");
        }

        List<AgentStep> steps = new ArrayList<>(stepsNode.size());
        for (int index = 0; index < stepsNode.size(); index++) {
            steps.add(parseStep(stepsNode.get(index), index));
        }
        return new AgentPlan(goal, steps);
    }

    private AgentStep parseStep(JsonNode stepNode, int index) {
        String path = "steps[" + index + "]";
        if (!stepNode.isObject()) {
            throw new AgentPlanParseException(path + " must be an object");
        }

        ObjectNode stepObject = (ObjectNode) stepNode;
        requireOnlyFields(stepObject, STEP_FIELDS, path);
        String id = requiredNonBlankText(stepObject, "id", path);
        String actionName = requiredNonBlankText(stepObject, "action", path);
        String description = requiredNonBlankText(stepObject, "description", path);
        String reason = requiredNonBlankText(stepObject, "reason", path);

        AgentAction action;
        try {
            action = AgentAction.valueOf(actionName);
        } catch (IllegalArgumentException exception) {
            throw new AgentPlanParseException(path + ".action is not a supported AgentAction");
        }

        JsonNode dependenciesNode = stepObject.get("dependsOn");
        if (dependenciesNode == null || !dependenciesNode.isArray()) {
            throw new AgentPlanParseException(path + ".dependsOn must be a string array");
        }
        List<String> dependencies = new ArrayList<>(dependenciesNode.size());
        for (int dependencyIndex = 0; dependencyIndex < dependenciesNode.size(); dependencyIndex++) {
            JsonNode dependency = dependenciesNode.get(dependencyIndex);
            if (!dependency.isTextual()) {
                throw new AgentPlanParseException(path + ".dependsOn must be a string array");
            }
            dependencies.add(dependency.asText());
        }

        return new AgentStep(
                id,
                action,
                description,
                reason,
                dependencies,
                AgentStepStatus.PENDING);
    }

    private String requiredNonBlankText(ObjectNode object, String field, String path) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new AgentPlanParseException(path + "." + field + " must be a non-blank string");
        }
        return value.asText();
    }

    private void requireOnlyFields(ObjectNode object, Set<String> expectedFields, String path) {
        for (String requiredField : expectedFields) {
            if (!object.has(requiredField)) {
                throw new AgentPlanParseException(path + "." + requiredField + " is required");
            }
        }
        Iterator<String> fields = object.fieldNames();
        while (fields.hasNext()) {
            if (!expectedFields.contains(fields.next())) {
                throw new AgentPlanParseException(path + " contains an unsupported field");
            }
        }
        if (object.size() != expectedFields.size()) {
            throw new AgentPlanParseException(path + " contains an unsupported field");
        }
    }
}

final class AgentPlanParseException extends RuntimeException {
    AgentPlanParseException(String message) {
        super(message);
    }

    AgentPlanParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
