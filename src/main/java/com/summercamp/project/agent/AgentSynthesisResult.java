package com.summercamp.project.agent;

import java.util.Objects;
import java.util.Optional;

/** Typed synthesis outcome that keeps malformed provider text outside the user response path. */
public record AgentSynthesisResult(
        Optional<AgentSynthesisEnvelope> envelope,
        Optional<AgentSynthesisParseError> parseError
) {
    public AgentSynthesisResult {
        envelope = envelope == null ? Optional.empty() : envelope;
        parseError = parseError == null ? Optional.empty() : parseError;
        if (envelope.isPresent() == parseError.isPresent()) {
            throw new IllegalArgumentException("exactly one of envelope or parseError is required");
        }
    }

    public static AgentSynthesisResult parsed(AgentSynthesisEnvelope envelope) {
        return new AgentSynthesisResult(
                Optional.of(Objects.requireNonNull(envelope)), Optional.empty());
    }

    public static AgentSynthesisResult answerOnly(String answer) {
        return parsed(new AgentSynthesisEnvelope(answer, AgentTrainingAudit.empty()));
    }

    public static AgentSynthesisResult invalid(AgentSynthesisParseError error) {
        return new AgentSynthesisResult(
                Optional.empty(), Optional.of(Objects.requireNonNull(error)));
    }

    public boolean parsed() {
        return envelope.isPresent();
    }

    public String answer() {
        return envelope.map(AgentSynthesisEnvelope::answer).orElse("");
    }

    public AgentTrainingAudit audit() {
        return envelope.map(AgentSynthesisEnvelope::audit).orElseGet(AgentTrainingAudit::empty);
    }
}
