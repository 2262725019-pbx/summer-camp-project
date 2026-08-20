package com.summercamp.project.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class CompleteTodoTool implements BotTool {

    private final TodoService todoService;
    private final ToolDefinition definition;

    public CompleteTodoTool(TodoService todoService, ObjectMapper objectMapper) {
        this.todoService = todoService;
        ObjectNode schema = objectMapper.createObjectNode().put("type", "object");
        schema.putObject("properties").putObject("index")
                .put("type", "integer")
                .put("description", "待办序号，从 1 开始")
                .put("minimum", 1)
                .put("maximum", TodoService.MAX_ITEMS_PER_USER);
        schema.putArray("required").add("index");
        schema.put("additionalProperties", false);
        definition = new ToolDefinition(
                "complete_todo",
                "按序号完成并移除当前用户的一条待办；不确定序号时先调用 list_todos。",
                schema);
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public ToolResult execute(JsonNode arguments, ToolContext context) {
        int index = arguments.path("index").asInt();
        String completed = todoService.complete(context.userId(), index);
        if (completed != null) {
            return ToolResult.text("已完成待办：" + completed);
        }
        List<String> remaining = todoService.list(context.userId());
        return remaining.isEmpty()
                ? ToolResult.text("当前没有待办事项。")
                : ToolResult.text("待办序号无效，当前共有 " + remaining.size() + " 项。");
    }
}
