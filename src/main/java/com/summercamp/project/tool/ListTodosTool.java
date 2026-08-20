package com.summercamp.project.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ListTodosTool implements BotTool {

    private final TodoService todoService;
    private final ToolDefinition definition;

    public ListTodosTool(TodoService todoService, ObjectMapper objectMapper) {
        this.todoService = todoService;
        ObjectNode schema = objectMapper.createObjectNode()
                .put("type", "object")
                .put("additionalProperties", false);
        definition = new ToolDefinition(
                "list_todos",
                "列出当前用户尚未完成的全部待办事项。",
                schema);
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public ToolResult execute(JsonNode arguments, ToolContext context) {
        List<String> items = todoService.list(context.userId());
        if (items.isEmpty()) {
            return ToolResult.text("你目前没有待办事项。");
        }
        StringBuilder result = new StringBuilder("你的待办事项：");
        for (int index = 0; index < items.size(); index++) {
            result.append('\n').append(index + 1).append(". ").append(items.get(index));
        }
        return ToolResult.text(result.toString());
    }
}
