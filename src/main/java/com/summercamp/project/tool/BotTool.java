package com.summercamp.project.tool;

import com.fasterxml.jackson.databind.JsonNode;

/** A locally implemented function that the model may request through Function Calling. */
public interface BotTool {

    ToolDefinition definition();

    ToolResult execute(JsonNode arguments, ToolContext context);
}
