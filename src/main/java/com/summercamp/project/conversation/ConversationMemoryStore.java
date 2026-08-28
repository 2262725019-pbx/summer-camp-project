package com.summercamp.project.conversation;

import com.summercamp.project.llm.ChatMessage;
import java.util.List;

public interface ConversationMemoryStore {

    List<ChatMessage> history(String userId);

    default MemoryContext recall(String userId, String currentQuery) {
        return MemoryContext.recentOnly(history(userId));
    }

    void recordExchange(String userId, String userText, String assistantText);

    void clear(String userId);
}
