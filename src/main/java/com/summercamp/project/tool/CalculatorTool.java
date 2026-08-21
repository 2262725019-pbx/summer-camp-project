package com.summercamp.project.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Locale;
import net.objecthunter.exp4j.ExpressionBuilder;
import org.springframework.stereotype.Component;

@Component
public class CalculatorTool implements BotTool {

    private static final BigDecimal MAX_ABSOLUTE_VALUE = BigDecimal.ONE.scaleByPowerOfTen(100);
    private static final int MAX_EXPRESSION_LENGTH = 200;

    private final ObjectMapper objectMapper;
    private final ToolDefinition definition;

    public CalculatorTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.definition = new ToolDefinition(
                "calculate",
                "安全计算数学表达式，支持加减乘除、括号、小数和常用数学函数；"
                        + "也兼容两个数字的精确四则运算。用户要求计算时使用。",
                schema(objectMapper));
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public boolean parallelSafe() {
        return true;
    }

    @Override
    public ToolResult execute(JsonNode arguments, ToolContext context) {
        String expression = arguments.path("expression").asText("").strip();
        if (!expression.isBlank()) {
            return evaluateExpression(expression);
        }
        BigDecimal left = requiredNumber(arguments, "left");
        BigDecimal right = requiredNumber(arguments, "right");
        ensureRange(left, "left");
        ensureRange(right, "right");
        String operator = requiredText(arguments, "operator").toUpperCase(Locale.ROOT);
        BigDecimal result = switch (operator) {
            case "ADD" -> left.add(right);
            case "SUBTRACT" -> left.subtract(right);
            case "MULTIPLY" -> left.multiply(right);
            case "DIVIDE" -> {
                if (right.compareTo(BigDecimal.ZERO) == 0) {
                    throw new ToolExecutionException("除数不能为 0");
                }
                yield left.divide(right, MathContext.DECIMAL128);
            }
            default -> throw new ToolExecutionException("不支持的运算类型：" + operator);
        };
        ensureRange(result, "result");
        ObjectNode response = objectMapper.createObjectNode();
        response.put("left", display(left));
        response.put("operator", operator);
        response.put("right", display(right));
        response.put("value", display(result));
        return ToolResult.data(response);
    }

    private ToolResult evaluateExpression(String expression) {
        if (expression.length() > MAX_EXPRESSION_LENGTH) {
            throw new ToolExecutionException("表达式不能超过 " + MAX_EXPRESSION_LENGTH + " 个字符");
        }
        try {
            double value = new ExpressionBuilder(expression).build().evaluate();
            if (!Double.isFinite(value)) {
                throw new ToolExecutionException("计算结果不是有限数字");
            }
            ObjectNode response = objectMapper.createObjectNode();
            response.put("expression", expression);
            response.put("value", formatDouble(value));
            return ToolResult.data(response);
        } catch (IllegalArgumentException exception) {
            throw new ToolExecutionException("表达式无法计算：" + exception.getMessage());
        }
    }

    private static ObjectNode schema(ObjectMapper objectMapper) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("expression")
                .put("type", "string")
                .put("description", "数学表达式，例如：12.5 * 3 + sqrt(9)")
                .put("minLength", 1)
                .put("maxLength", MAX_EXPRESSION_LENGTH);
        properties.putObject("left")
                .put("type", "number")
                .put("description", "兼容模式的左侧数字");
        properties.putObject("operator")
                .put("type", "string")
                .put("description", "兼容模式的运算类型")
                .putArray("enum")
                .add("ADD")
                .add("SUBTRACT")
                .add("MULTIPLY")
                .add("DIVIDE");
        properties.putObject("right")
                .put("type", "number")
                .put("description", "兼容模式的右侧数字");
        schema.putArray("required");
        schema.put("additionalProperties", false);
        return schema;
    }

    private BigDecimal requiredNumber(JsonNode arguments, String name) {
        JsonNode value = arguments.get(name);
        if (value == null || !value.isNumber()) {
            throw new ToolExecutionException("参数 " + name + " 必须是数字");
        }
        return value.decimalValue();
    }

    private String requiredText(JsonNode arguments, String name) {
        String value = arguments.path(name).asText().strip();
        if (value.isBlank()) {
            throw new ToolExecutionException("缺少参数：" + name);
        }
        return value;
    }

    private void ensureRange(BigDecimal value, String name) {
        if (value.abs().compareTo(MAX_ABSOLUTE_VALUE) > 0) {
            throw new ToolExecutionException("参数 " + name + " 超出允许范围");
        }
    }

    private String display(BigDecimal value) {
        BigDecimal normalized = value.stripTrailingZeros();
        return normalized.compareTo(BigDecimal.ZERO) == 0
                ? "0"
                : normalized.toPlainString();
    }

    private String formatDouble(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return Long.toString((long) value);
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
