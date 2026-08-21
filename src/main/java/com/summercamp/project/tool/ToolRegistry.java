package com.summercamp.project.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Allow-list dispatcher for model-requested tools. */
@Component
public class ToolRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(ToolRegistry.class);
    private static final int MAX_ARGUMENT_CHARACTERS = 4_000;
    private static final int MAX_ERROR_CHARACTERS = 300;

    private final ObjectMapper objectMapper;
    private final Map<String, BotTool> tools;

    public ToolRegistry(List<BotTool> tools, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        Map<String, BotTool> registered = new LinkedHashMap<>();
        tools.stream()
                .sorted((left, right) -> left.definition().name()
                        .compareTo(right.definition().name()))
                .forEach(tool -> {
                    String name = tool.definition().name();
                    if (registered.putIfAbsent(name, tool) != null) {
                        throw new IllegalStateException("工具名称重复：" + name);
                    }
                });
        this.tools = Collections.unmodifiableMap(new LinkedHashMap<>(registered));
    }

    public List<ToolDefinition> definitions() {
        return tools.values().stream().map(BotTool::definition).toList();
    }

    /** 未知工具和未显式声明的工具一律不允许并行。 */
    public boolean isParallelSafe(String name) {
        BotTool tool = tools.get(name);
        return tool != null && tool.parallelSafe();
    }

    /** 执行工具并保留图片、直接完成等富结果，供多步 Function Calling 使用。 */
    public Invocation invoke(String name, String argumentJson, ToolContext context) {
        long startedAt = System.nanoTime();
        try {
            ToolResult result = executeTool(name, argumentJson, context);
            String content = serialize(successEnvelope(result));
            LOGGER.info("工具执行成功：{}，耗时={}ms", name, elapsedMillis(startedAt));
            return new Invocation(true, result, content);
        } catch (JsonProcessingException exception) {
            String error = "工具参数不是有效 JSON";
            LOGGER.warn("工具参数解析失败：{}，耗时={}ms", safeName(name), elapsedMillis(startedAt));
            return failure(error);
        } catch (RuntimeException exception) {
            String error = safeError(exception.getMessage());
            LOGGER.warn("工具执行失败：{}，耗时={}ms，原因：{}",
                    safeName(name), elapsedMillis(startedAt), error);
            return failure(error);
        }
    }

    private ToolResult executeTool(String name, String argumentJson, ToolContext context)
            throws JsonProcessingException {
        BotTool tool = tools.get(name);
        if (tool == null) {
            throw new ToolExecutionException("不允许调用未知工具：" + safeName(name));
        }
        String normalized = argumentJson == null ? "{}" : argumentJson.strip();
        if (normalized.length() > MAX_ARGUMENT_CHARACTERS) {
            throw new ToolExecutionException("工具参数过长");
        }
        JsonNode arguments = objectMapper.readTree(normalized.isBlank() ? "{}" : normalized);
        if (arguments == null || !arguments.isObject()) {
            throw new ToolExecutionException("工具参数必须是 JSON 对象");
        }
        validateSchema(tool.definition(), arguments);
        ToolResult result = tool.execute(arguments, context == null ? ToolContext.anonymous() : context);
        if (result == null) {
            throw new ToolExecutionException("工具没有返回执行结果");
        }
        return result;
    }

    private Invocation failure(String error) {
        ToolResult result = ToolResult.text(error);
        return new Invocation(false, result, serialize(failureEnvelope(error)));
    }

    private ObjectNode successEnvelope(ToolResult result) {
        ObjectNode envelope = objectMapper.createObjectNode().put("success", true);
        switch (result) {
            case ToolResult.Text text -> envelope.put("result", text.content());
            case ToolResult.Data data -> envelope.set("result", data.content());
            case ToolResult.Completed completed -> envelope.put("result", completed.reply());
            case ToolResult.Image image -> envelope.putObject("result")
                    .put("type", "image")
                    .put("file_name", image.fileName())
                    .put("caption", image.caption());
        }
        return envelope;
    }

    private ObjectNode failureEnvelope(String error) {
        return objectMapper.createObjectNode()
                .put("success", false)
                .put("error", safeError(error));
    }

    private String serialize(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化工具执行结果", exception);
        }
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private void validateSchema(ToolDefinition definition, JsonNode arguments) {
        JsonNode properties = definition.parameters().path("properties");
        if (!definition.parameters().path("additionalProperties").asBoolean(true)) {
            arguments.properties().forEach(entry -> {
                if (!properties.has(entry.getKey())) {
                    throw new ToolExecutionException("不允许的工具参数：" + entry.getKey());
                }
            });
        }
        for (JsonNode required : definition.parameters().path("required")) {
            String field = required.asText();
            if (!arguments.has(field) || arguments.path(field).isNull()) {
                throw new ToolExecutionException("缺少参数：" + field);
            }
        }
        arguments.properties().forEach(entry -> {
            JsonNode fieldSchema = properties.path(entry.getKey());
            if (!fieldSchema.isMissingNode()) {
                validateValue(entry.getKey(), entry.getValue(), fieldSchema);
            }
        });
    }

    private void validateValue(String field, JsonNode value, JsonNode schema) {
        String type = schema.path("type").asText();
        boolean correctType = switch (type) {
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "array" -> value.isArray();
            case "object" -> value.isObject();
            case "" -> true;
            default -> throw new ToolExecutionException("工具参数 Schema 类型不受支持：" + type);
        };
        if (!correctType) {
            throw new ToolExecutionException("参数 " + field + " 类型必须是 " + type);
        }
        JsonNode allowedValues = schema.path("enum");
        if (allowedValues.isArray() && allowedValues.valueStream().noneMatch(value::equals)) {
            throw new ToolExecutionException("参数 " + field + " 不在允许范围内");
        }
        if (value.isTextual()) {
            int length = value.textValue().length();
            if (schema.has("minLength") && length < schema.path("minLength").asInt()) {
                throw new ToolExecutionException("参数 " + field + " 太短");
            }
            if (schema.has("maxLength") && length > schema.path("maxLength").asInt()) {
                throw new ToolExecutionException("参数 " + field + " 太长");
            }
        }
        if (value.isNumber()) {
            if (schema.has("minimum")
                    && value.decimalValue().compareTo(schema.path("minimum").decimalValue()) < 0) {
                throw new ToolExecutionException("参数 " + field + " 小于最小值");
            }
            if (schema.has("maximum")
                    && value.decimalValue().compareTo(schema.path("maximum").decimalValue()) > 0) {
                throw new ToolExecutionException("参数 " + field + " 大于最大值");
            }
        }
    }

    private String safeName(String name) {
        return name == null || name.isBlank() ? "<empty>" : name.strip();
    }

    private String safeError(String message) {
        String normalized = message == null || message.isBlank()
                ? "工具执行失败"
                : message.replaceAll("\\s+", " ").strip();
        return normalized.length() <= MAX_ERROR_CHARACTERS
                ? normalized
                : normalized.substring(0, MAX_ERROR_CHARACTERS) + "…";
    }

    public record Invocation(boolean success, ToolResult result, String modelContent) {
    }
}
