package com.summercamp.project.conversation;

import java.time.Instant;

/** Immutable user/assistant exchange; binary media and provider payloads are never stored. */
public record ConversationMemoryEntry(
        String entryId,
        String userText,
        String assistantText,
        Instant createdAt) {

    public ConversationMemoryEntry {
        if (entryId == null || entryId.isBlank() || createdAt == null) {
            throw new IllegalArgumentException("Memory entry id and creation time are required");
        }
        userText = userText == null ? "" : userText;
        assistantText = assistantText == null ? "" : assistantText;
    }
}
