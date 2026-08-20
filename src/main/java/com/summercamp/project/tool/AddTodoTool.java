package com.summercamp.project.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

@Component
public class AddTodoTool implements BotTool {

    private final TodoService todoService;
    private final ToolDefinition definition;

    public AddTodoTool(TodoService todoService, ObjectMapper objectMapper) {
        this.todoService = todoService;
        ObjectNode schema = objectMapper.createObjectNode().put("type", "object");
        schema.putObject("properties").putObject("item")
                .put("type", "string")
                .put("description", "要记录的待办内容")
                .put("minLength", 1)
                .put("maxLength", TodoService.MAX_ITEM_CHARACTERS);
        schema.putArray("required").add("item");
        schema.put("additionalProperties", false);
        definition = new ToolDefinition(
                "add_todo",
                "为当前用户添加待办事项。用户说记一下、加入待办或提醒要做某事时使用。",
                schema);
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public ToolResult execute(JsonNode arguments, ToolContext context) {
        String item = arguments.path("item").asText();
        int index = todoService.add(context.userId(), item);
        return ToolResult.text("已添加第 " + index + " 项待办：" + item.strip());
    }
}
