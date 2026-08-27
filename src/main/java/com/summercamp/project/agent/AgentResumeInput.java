package com.summercamp.project.agent;

/** Typed, application-controlled supplement for one recoverable Agent step. */
public record AgentResumeInput(String waitingStepId, String supplementText, int attempt) {

    public AgentResumeInput {
        if (waitingStepId == null || waitingStepId.isBlank()) {
            throw new IllegalArgumentException("waitingStepId must not be blank");
        }
        if (supplementText == null || supplementText.isBlank()) {
            throw new IllegalArgumentException("supplementText must not be blank");
        }
        supplementText = supplementText.strip();
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be positive");
        }
    }
}
