package com.summercamp.project.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.summercamp.project.conversation.ConversationMemoryStore;
import com.summercamp.project.intent.PendingWeatherRequestStore;
import org.springframework.stereotype.Component;

@Component
public class ClearMemoryTool implements BotTool {

    private final ConversationMemoryStore memoryStore;
    private final PendingWeatherRequestStore pendingWeatherStore;
    private final ToolDefinition definition;

    public ClearMemoryTool(
            ConversationMemoryStore memoryStore,
            PendingWeatherRequestStore pendingWeatherStore,
            ObjectMapper objectMapper) {
        this.memoryStore = memoryStore;
        this.pendingWeatherStore = pendingWeatherStore;
        ObjectNode schema = objectMapper.createObjectNode()
                .put("type", "object")
                .put("additionalProperties", false);
        definition = new ToolDefinition(
                "clear_memory",
                "清除当前用户的对话上下文和等待补充的天气请求。用户要求忘掉之前内容或重新开始时使用。",
                schema);
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public ToolResult execute(JsonNode arguments, ToolContext context) {
        if (context.userId().isBlank()) {
            throw new ToolExecutionException("当前工具需要有效的用户会话");
        }
        memoryStore.clear(context.userId());
        pendingWeatherStore.clear(context.userId());
        return ToolResult.completed("已清除你的对话上下文和待处理请求。");
    }
}
