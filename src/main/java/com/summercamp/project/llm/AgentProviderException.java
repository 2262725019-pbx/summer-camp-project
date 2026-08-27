package com.summercamp.project.llm;

import java.util.Objects;

/** Provider-attempt exhaustion with a typed, non-sensitive reason. */
public final class AgentProviderException extends LlmException {
    private final String operation;
    private final AgentProviderFailureCategory category;

    public AgentProviderException(
            String operation,
            AgentProviderFailureCategory category,
            Throwable cause
    ) {
        super("Agent provider failed: " + safeOperation(operation) + "_"
                + Objects.requireNonNull(category, "category must not be null").name(), cause);
        this.operation = safeOperation(operation);
        this.category = category;
    }

    public String operation() {
        return operation;
    }

    public AgentProviderFailureCategory category() {
        return category;
    }

    private static String safeOperation(String value) {
        String normalized = value == null ? "" : value.strip().toUpperCase();
        if (normalized.isBlank() || !normalized.matches("[A-Z_]+")) {
            throw new IllegalArgumentException("operation must be a stable code");
        }
        return normalized;
    }
}
