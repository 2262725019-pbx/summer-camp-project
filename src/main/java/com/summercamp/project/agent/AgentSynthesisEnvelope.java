package com.summercamp.project.agent;

import java.util.Objects;

/** Parsed Agent-only provider envelope. Only {@code answer} is user-visible. */
public record AgentSynthesisEnvelope(
        String answer,
        AgentTrainingAudit audit
) {
    public AgentSynthesisEnvelope {
        if (answer == null || answer.isBlank()) {
            throw new IllegalArgumentException("answer must not be blank");
        }
        answer = answer.strip();
        audit = Objects.requireNonNull(audit, "audit must not be null");
    }
}
