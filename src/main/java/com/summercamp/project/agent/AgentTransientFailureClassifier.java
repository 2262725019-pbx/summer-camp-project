package com.summercamp.project.agent;

import com.summercamp.project.llm.AgentProviderException;
import com.summercamp.project.llm.AgentProviderFailureCategory;
import java.util.Optional;

/** Fail-closed classification for the deterministic Agent fallback boundary. */
public final class AgentTransientFailureClassifier {

    public Optional<AgentFallbackReason> classify(Throwable failure) {
        AgentProviderException providerFailure = findCause(failure, AgentProviderException.class);
        if (providerFailure == null) {
            return Optional.empty();
        }
        return switch (providerFailure.category()) {
            case RATE_LIMIT -> Optional.of(AgentFallbackReason.RATE_LIMIT);
            case TIMEOUT -> Optional.of(AgentFallbackReason.TIMEOUT);
            case CONNECTIVITY -> Optional.of(AgentFallbackReason.CONNECTIVITY);
            case SERVER_ERROR -> Optional.of(AgentFallbackReason.SERVER_ERROR);
            case INVALID_PROVIDER_RESPONSE, INTERRUPTED, NON_RETRYABLE,
                    UNKNOWN_PROVIDER_FAILURE -> Optional.empty();
        };
    }

    private <T extends Throwable> T findCause(Throwable failure, Class<T> type) {
        Throwable current = failure;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }
}
