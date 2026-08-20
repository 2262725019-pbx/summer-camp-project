package com.summercamp.project.llm;

import com.summercamp.project.tool.ToolContext;

public interface ChatModelClient {
    ChatOutcome chat(ChatRequest request, ToolContext context);
}
