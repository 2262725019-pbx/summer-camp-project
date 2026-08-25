package com.summercamp.project.agent;

import java.util.Objects;

public final class AgentExecutionException extends RuntimeException {
    private final AgentExecutionFailureReason reason;

    public AgentExecutionException(AgentExecutionFailureReason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
    }

    public AgentExecutionException(
            AgentExecutionFailureReason reason,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
    }

    public AgentExecutionFailureReason reason() {
        return reason;
    }
}
