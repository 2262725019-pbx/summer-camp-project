package com.summercamp.project.conversation;

import com.summercamp.project.llm.ChatMessage;
import java.util.List;

public record MemoryContext(
        List<ChatMessage> recentMessages,
        List<MemoryHit> recalledEntries,
        List<SessionFact> sessionFacts,
        String factPromptContext,
        String promptContext,
        Diagnostics diagnostics) {

    public MemoryContext {
        recentMessages = List.copyOf(recentMessages);
        recalledEntries = List.copyOf(recalledEntries);
        sessionFacts = List.copyOf(sessionFacts);
        factPromptContext = factPromptContext == null ? "" : factPromptContext.strip();
        promptContext = promptContext == null ? "" : promptContext.strip();
        diagnostics = diagnostics == null ? Diagnostics.empty() : diagnostics;
    }

    public static MemoryContext recentOnly(List<ChatMessage> messages) {
        List<ChatMessage> copied = List.copyOf(messages);
        return new MemoryContext(
                copied,
                List.of(),
                List.of(),
                "",
                "",
                new Diagnostics(copied.size(), 1, 0, 0, 0, 0, 0, 0, 0));
    }

    public record MemoryHit(ConversationMemoryEntry entry, int score, String source) {

        public MemoryHit(ConversationMemoryEntry entry, int score) {
            this(entry, score, "CONVERSATION_SESSION");
        }

        public MemoryHit {
            source = source == null || source.isBlank() ? "CONVERSATION_SESSION" : source;
        }
    }

    public record Diagnostics(
            int memoryRecentMessages,
            int memoryRecallQueries,
            int memoryRecalledEntries,
            int memoryRecallContextChars,
            int memoryTopScore,
            int memorySessionFacts,
            int memoryFactsExtracted,
            int memoryFactsUpdated,
            int memoryFactsRemoved) {

        static Diagnostics empty() {
            return new Diagnostics(0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }
}
