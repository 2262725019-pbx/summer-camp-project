package com.summercamp.project.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.summercamp.project.llm.GeneratedImage;
import com.summercamp.project.llm.ImageGenerationClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class GenerateImageTool implements BotTool {

    private static final int MAX_PROMPT_CHARACTERS = 2_000;

    private final ObjectProvider<ImageGenerationClient> imageClientProvider;
    private final ToolDefinition definition;

    public GenerateImageTool(
            ObjectProvider<ImageGenerationClient> imageClientProvider,
            ObjectMapper objectMapper) {
        this.imageClientProvider = imageClientProvider;
        ObjectNode schema = objectMapper.createObjectNode().put("type", "object");
        schema.putObject("properties").putObject("prompt")
                .put("type", "string")
                .put("description", "需要生成的图片内容描述")
                .put("minLength", 1)
                .put("maxLength", MAX_PROMPT_CHARACTERS);
        schema.putArray("required").add("prompt");
        schema.put("additionalProperties", false);
        definition = new ToolDefinition(
                "generate_image",
                "根据描述生成图片。当用户要求画图、制作图片，或多步任务需要图片输出时使用。",
                schema);
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public ToolResult execute(JsonNode arguments, ToolContext context) {
        String prompt = arguments.path("prompt").asText().strip();
        ImageGenerationClient imageClient = imageClientProvider.getIfAvailable();
        if (imageClient == null) {
            throw new ToolExecutionException("图片生成服务当前不可用");
        }
        GeneratedImage image = imageClient.generate(context.history(), prompt);
        return ToolResult.image(image.data(), image.fileName(), "已根据描述生成图片：" + prompt);
    }
}
