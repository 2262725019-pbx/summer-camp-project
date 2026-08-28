package com.summercamp.project.conversation;

import java.time.Instant;

/** Immutable current session fact. The original user message is deliberately not retained. */
public record SessionFact(
        SessionFactKey key,
        String value,
        Instant updatedAt,
        String sourceEntryId,
        SessionFactSourceType sourceType) {

    public SessionFact {
        if (key == null
                || value == null
                || value.isBlank()
                || updatedAt == null
                || sourceEntryId == null
                || sourceEntryId.isBlank()
                || sourceType == null) {
            throw new IllegalArgumentException("Session fact fields must not be blank");
        }
        value = value.strip();
    }
}
