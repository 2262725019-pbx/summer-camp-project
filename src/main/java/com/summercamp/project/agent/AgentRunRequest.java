package com.summercamp.project.agent;

import com.summercamp.project.llm.ChatMessage;
import java.util.List;

public record AgentRunRequest(
        String userId,
        String goal,
        List<ChatMessage> history,
        boolean voiceMessage
) {
    public AgentRunRequest {
        userId = userId == null ? "" : userId;
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("goal must not be blank");
        }
        goal = goal.strip();
        history = history == null ? List.of() : List.copyOf(history);
    }
}
