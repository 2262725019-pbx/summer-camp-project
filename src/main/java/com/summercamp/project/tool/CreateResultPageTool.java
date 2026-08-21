package com.summercamp.project.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.summercamp.project.result.CalculationResultPage;
import com.summercamp.project.result.ResultPageService;
import org.springframework.stereotype.Component;

@Component
public class CreateResultPageTool implements BotTool {

    private static final int MAX_LENGTH = 2_000;

    private final ResultPageService resultPageService;
    private final ToolDefinition definition;

    public CreateResultPageTool(ObjectMapper objectMapper, ResultPageService resultPageService) {
        this.resultPageService = resultPageService;
        ObjectNode schema = objectMapper.createObjectNode().put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("title")
                .put("type", "string")
                .put("description", "页面标题，默认‘计算结果’")
                .put("maxLength", 100);
        properties.putObject("expression")
                .put("type", "string")
                .put("description", "计算工具返回的原始表达式")
                .put("minLength", 1)
                .put("maxLength", MAX_LENGTH);
        properties.putObject("result")
                .put("type", "string")
                .put("description", "计算工具返回的最终数值")
                .put("minLength", 1)
                .put("maxLength", MAX_LENGTH);
        schema.putArray("required").add("expression").add("result");
        schema.put("additionalProperties", false);
        definition = new ToolDefinition(
                "create_result_page",
                "创建一个手机浏览器可访问的临时计算结果页面，返回页面 URL。"
                        + "当用户要求把计算结果生成可访问的二维码时，先调用本工具，再把返回的 URL 交给二维码工具。",
                schema);
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public ToolResult execute(JsonNode arguments, ToolContext context) {
        try {
            CalculationResultPage page = resultPageService.create(
                    arguments.path("title").asText("计算结果"),
                    arguments.path("expression").asText(),
                    arguments.path("result").asText());
            ObjectNode response = JsonNodeFactory.instance.objectNode();
            response.put("url", resultPageService.publicUrl(page));
            response.put("expiresAt", page.expiresAt().toString());
            response.put("message", "结果页已创建，请把 url 传给 generate_qr_code");
            return ToolResult.data(response);
        } catch (IllegalArgumentException exception) {
            throw new ToolExecutionException(exception.getMessage());
        }
    }
}
