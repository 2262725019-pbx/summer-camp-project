package com.summercamp.project.conversation;

import com.summercamp.project.llm.ChatMessage;
import java.util.List;

public interface ConversationMemoryStore {

    List<ChatMessage> history(String userId);

    void recordExchange(String userId, String userText, String assistantText);

    void clear(String userId);
}
