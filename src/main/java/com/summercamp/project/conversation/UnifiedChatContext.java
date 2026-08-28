package com.summercamp.project.conversation;

import com.summercamp.project.llm.ChatMessage;
import com.summercamp.project.rag.RagContext;
import java.util.ArrayList;
import java.util.List;

/** Typed final context while preserving the semantic boundary of every source. */
public record UnifiedChatContext(
        List<ChatMessage> recentMessages,
        List<MemoryContext.MemoryHit> recalledMemories,
        List<SessionFact> sessionFacts,
        List<RagContext.Hit> ragHits,
        String memoryGrounding,
        String sessionFactGrounding,
        String ragGrounding,
        Diagnostics diagnostics) {

    public UnifiedChatContext {
        recentMessages = List.copyOf(recentMessages);
        recalledMemories = List.copyOf(recalledMemories);
        sessionFacts = List.copyOf(sessionFacts);
        ragHits = List.copyOf(ragHits);
        memoryGrounding = safe(memoryGrounding);
        sessionFactGrounding = safe(sessionFactGrounding);
        ragGrounding = safe(ragGrounding);
        diagnostics = diagnostics == null ? Diagnostics.empty() : diagnostics;
    }

    public String groundingContext() {
        List<String> blocks = new ArrayList<>(3);
        if (!memoryGrounding.isBlank()) {
            blocks.add(memoryGrounding);
        }
        if (!ragGrounding.isBlank()) {
            blocks.add(ragGrounding);
        }
        if (!sessionFactGrounding.isBlank()) {
            blocks.add(sessionFactGrounding);
        }
        return String.join("\n\n", blocks);
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }

    public record Diagnostics(
            int ragHitsIncluded,
            int ragHitsDropped,
            int memoryRecallIncluded,
            int memoryRecallDropped,
            int factCountIncluded,
            int factCountDropped,
            int contextRecentChars,
            int contextRecallChars,
            int contextFactChars,
            int contextRagChars,
            int contextTotalChars,
            int contextRagHits,
            int contextMemoryHits,
            int ragQueries,
            int memoryRecallQueries) {

        static Diagnostics empty() {
            return new Diagnostics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }
}
