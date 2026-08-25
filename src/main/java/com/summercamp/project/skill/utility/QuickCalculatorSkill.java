package com.summercamp.project.skill.utility;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.summercamp.project.skill.BotSkill;
import com.summercamp.project.skill.SkillContext;
import com.summercamp.project.skill.SkillResult;
import com.summercamp.project.tool.CalculatorTool;
import com.summercamp.project.tool.ToolContext;
import com.summercamp.project.tool.ToolExecutionException;
import com.summercamp.project.tool.ToolResult;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class QuickCalculatorSkill implements BotSkill {

    public static final String SKILL_NAME = "quick-calculator";

    private static final List<String> TRIGGER_TERMS = List.of(
            "计算", "算一下", "帮我算", "等于多少", "帮我计算", "帮忙算", "算一算");
    private static final List<String> CHAIN_TERMS = List.of(
            "然后", "接着", "再把", "并且", "同时", "二维码", "图片", "天气", "待办");
    private static final Pattern PREFIX = Pattern.compile(
            "^(?:请|麻烦|你|帮我|帮忙|给我)?\\s*(?:计算|算一下|算一算|帮忙算|帮我计算)\\s*[：:]?\\s*");
    private static final Pattern SUFFIX = Pattern.compile(
            "\\s*(?:等于多少|是多少|呢|啊|吧|吗|么)?[。！!？?\\s]*$");

    private final CalculatorTool calculatorTool;
    private final ObjectMapper objectMapper;

    public QuickCalculatorSkill(CalculatorTool calculatorTool, ObjectMapper objectMapper) {
        this.calculatorTool = calculatorTool;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return SKILL_NAME;
    }

    @Override
    public int priority() {
        return 70;
    }

    @Override
    public int matchScore(String text) {
        String normalized = normalize(text);
        if (normalized.isBlank() || CHAIN_TERMS.stream().anyMatch(normalized::contains)) {
            return 0;
        }
        int longest = TRIGGER_TERMS.stream()
                .filter(normalized::contains)
                .mapToInt(String::length)
                .max()
                .orElse(0);
        return longest == 0 ? 0 : 70 + longest;
    }

    @Override
    public SkillResult execute(SkillContext context) {
        String expression = extractExpression(context.text());
        if (expression.isBlank()) {
            return SkillResult.waitingInput("没有识别到数学表达式，请发送类似“计算 125*36”的消息。");
        }
        ObjectNode arguments = objectMapper.createObjectNode().put("expression", expression);
        try {
            ToolResult result = calculatorTool.execute(
                    arguments,
                    new ToolContext(context.userId(), context.text(), context.history()));
            ToolResult.Data data = (ToolResult.Data) result;
            return SkillResult.completed(
                    data.content().path("expression").asText(expression)
                            + " = " + data.content().path("value").asText());
        } catch (ToolExecutionException exception) {
            return SkillResult.completed("无法完成计算：" + exception.getMessage());
        }
    }

    private String extractExpression(String text) {
        if (text == null) {
            return "";
        }
        String expression = PREFIX.matcher(text).replaceFirst("");
        expression = SUFFIX.matcher(expression).replaceFirst("").strip();
        expression = expression
                .replace("乘以", "*")
                .replace("乘", "*")
                .replace("除以", "/")
                .replace("除", "/")
                .replace("加上", "+")
                .replace("减去", "-");
        Matcher matcher = Pattern.compile("[0-9+\\-*/().%a-zA-Z\\s]+")
                .matcher(expression);
        String best = "";
        while (matcher.find()) {
            String candidate = matcher.group().strip();
            if (candidate.matches(".*\\d.*") && candidate.length() > best.length()) {
                best = candidate;
            }
        }
        return best.replaceAll("^[+\\-*/\\s]+|[+\\-*/\\s]+$", "").strip();
    }

    private String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }
}
