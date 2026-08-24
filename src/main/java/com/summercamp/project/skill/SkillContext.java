package com.summercamp.project.skill;

import com.summercamp.project.llm.ChatMessage;
import java.util.List;

public record SkillContext(
        String userId,
        String text,
        List<ChatMessage> history,
        boolean voiceMessage) {

    public SkillContext {
        userId = userId == null ? "" : userId;
        text = text == null ? "" : text;
        history = List.copyOf(history);
    }
}
