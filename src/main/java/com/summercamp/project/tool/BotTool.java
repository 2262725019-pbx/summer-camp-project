package com.summercamp.project.tool;

import com.fasterxml.jackson.databind.JsonNode;

/** A locally implemented function that the model may request through Function Calling. */
public interface BotTool {

    ToolDefinition definition();

    ToolResult execute(JsonNode arguments, ToolContext context);

    /**
     * 同一模型轮次出现多个工具调用时，是否允许与其他安全工具并行执行。
     * 默认关闭，避免新增的有状态工具被误并行。
     */
    default boolean parallelSafe() {
        return false;
    }
}
