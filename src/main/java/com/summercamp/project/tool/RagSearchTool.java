package com.summercamp.project.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.summercamp.project.rag.RagContext;
import com.summercamp.project.rag.RagRetriever;
import org.springframework.stereotype.Component;

@Component
public class RagSearchTool implements BotTool {

    private static final int MAX_QUERY_LENGTH = 200;

    private final RagRetriever ragRetriever;
    private final ObjectMapper objectMapper;
    private final ToolDefinition definition;

    public RagSearchTool(RagRetriever ragRetriever, ObjectMapper objectMapper) {
        this.ragRetriever = ragRetriever;
        this.objectMapper = objectMapper;

        ObjectNode schema = objectMapper.createObjectNode().put("type", "object");
        schema.putObject("properties").putObject("query")
            .put("type", "string")
            .put("description", "要检索的问题或关键词")
            .put("minLength", 1)
            .put("maxLength", MAX_QUERY_LENGTH);
        schema.putArray("required").add("query");
        schema.put("additionalProperties", false);

        definition = new ToolDefinition(
            "search_knowledge",
            "从本地知识库检索与健康、项目使用等相关的资料。当需要获取事实性知识或补充背景信息时使用。",
            schema);
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
        String query = arguments.path("query").asText().strip();
        RagContext ragContext = ragRetriever.retrieve(query);
        if (!ragContext.matched()) {
            return ToolResult.text("未找到相关知识。");
        }
        ObjectNode result = objectMapper.createObjectNode();
        result.put("matched_documents", ragContext.documentIds().toString());
        result.put("content", ragContext.promptContext());
        return ToolResult.data(result);
    }
}
