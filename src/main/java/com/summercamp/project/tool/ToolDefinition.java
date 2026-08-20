package com.summercamp.project.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;
import java.util.regex.Pattern;

/** Function name, description and JSON Schema sent to the model. */
public record ToolDefinition(String name, String description, JsonNode parameters) {

    private static final Pattern VALID_NAME = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]{0,63}$");

    public ToolDefinition {
        name = Objects.requireNonNull(name, "name").strip();
        description = Objects.requireNonNull(description, "description").strip();
        parameters = Objects.requireNonNull(parameters, "parameters").deepCopy();
        if (!VALID_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("工具名称不合法：" + name);
        }
        if (description.isBlank()) {
            throw new IllegalArgumentException("工具描述不能为空");
        }
        if (!parameters.isObject() || !"object".equals(parameters.path("type").asText())) {
            throw new IllegalArgumentException("工具参数必须使用 object 类型的 JSON Schema");
        }
    }

    public ObjectNode toApiJson(ObjectMapper objectMapper) {
        ObjectNode function = objectMapper.createObjectNode();
        function.put("name", name);
        function.put("description", description);
        function.set("parameters", parameters.deepCopy());
        return objectMapper.createObjectNode()
                .put("type", "function")
                .set("function", function);
    }
}
