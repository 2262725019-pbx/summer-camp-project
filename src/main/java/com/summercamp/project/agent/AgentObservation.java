package com.summercamp.project.agent;

import java.util.Map;

/**
 * A compact, text-only step result. Binary payloads belong in external storage,
 * with only their reference recorded in {@code structuredData}.
 */
public record AgentObservation(
        String stepId,
        boolean success,
        String summary,
        Map<String, String> structuredData
) {
    public AgentObservation {
        if (stepId == null || stepId.isBlank()) {
            throw new IllegalArgumentException("stepId must not be blank");
        }
        summary = summary == null ? "" : summary;
        structuredData = structuredData == null ? Map.of() : Map.copyOf(structuredData);
    }

    public AgentObservation(String stepId, boolean success, String summary) {
        this(stepId, success, summary, Map.of());
    }
}
