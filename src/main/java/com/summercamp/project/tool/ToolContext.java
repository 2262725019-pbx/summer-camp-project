package com.summercamp.project.tool;

import com.summercamp.project.agent.AgentRunMetrics;
import com.summercamp.project.llm.ChatMessage;
import java.util.List;

/** 会话级工具上下文，供待办、上下文和图片生成等有状态工具使用。 */
public record ToolContext(
        String userId,
        String userText,
        List<ChatMessage> history,
        AgentRunMetrics metrics
) {

    public ToolContext {
        userId = userId == null ? "" : userId;
        userText = userText == null ? "" : userText;
        history = history == null ? List.of() : List.copyOf(history);
        metrics = metrics == null ? AgentRunMetrics.unobserved() : metrics;
    }

    public ToolContext(String userId, String userText, List<ChatMessage> history) {
        this(userId, userText, history, AgentRunMetrics.unobserved());
    }

    public ToolContext(String userId, String userText) {
        this(userId, userText, List.of(), AgentRunMetrics.unobserved());
    }

    public static ToolContext anonymous() {
        return new ToolContext("", "", List.of(), AgentRunMetrics.unobserved());
    }
}
