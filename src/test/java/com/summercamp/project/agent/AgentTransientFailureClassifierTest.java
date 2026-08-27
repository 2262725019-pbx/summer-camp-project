package com.summercamp.project.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.summercamp.project.llm.AgentProviderException;
import com.summercamp.project.llm.AgentProviderFailureCategory;
import org.junit.jupiter.api.Test;

class AgentTransientFailureClassifierTest {
    private final AgentTransientFailureClassifier classifier =
            new AgentTransientFailureClassifier();

    @Test
    void allowsOnlyTypedTransientProviderFailures() {
        assertEquals(AgentFallbackReason.TIMEOUT, classify(AgentProviderFailureCategory.TIMEOUT));
        assertEquals(AgentFallbackReason.CONNECTIVITY,
                classify(AgentProviderFailureCategory.CONNECTIVITY));
        assertEquals(AgentFallbackReason.RATE_LIMIT,
                classify(AgentProviderFailureCategory.RATE_LIMIT));
        assertEquals(AgentFallbackReason.SERVER_ERROR,
                classify(AgentProviderFailureCategory.SERVER_ERROR));
        for (AgentProviderFailureCategory category : ListSupport.NON_TRANSIENT) {
            assertTrue(classifier.classify(
                    new AgentProviderException("PLANNING", category, null)).isEmpty());
        }
        assertTrue(classifier.classify(new IllegalStateException("configuration")).isEmpty());
    }

    private AgentFallbackReason classify(AgentProviderFailureCategory category) {
        return classifier.classify(new AgentProviderException("PLANNING", category, null))
                .orElseThrow();
    }

    private static final class ListSupport {
        private static final java.util.List<AgentProviderFailureCategory> NON_TRANSIENT =
                java.util.List.of(
                        AgentProviderFailureCategory.NON_RETRYABLE,
                        AgentProviderFailureCategory.INVALID_PROVIDER_RESPONSE,
                        AgentProviderFailureCategory.INTERRUPTED,
                        AgentProviderFailureCategory.UNKNOWN_PROVIDER_FAILURE);
    }
}
