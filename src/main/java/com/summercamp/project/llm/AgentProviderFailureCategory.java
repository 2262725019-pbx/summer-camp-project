package com.summercamp.project.llm;

/** Stable, content-free failure category exposed at the Agent/provider boundary. */
public enum AgentProviderFailureCategory {
    TIMEOUT,
    CONNECTIVITY,
    RATE_LIMIT,
    SERVER_ERROR,
    INVALID_PROVIDER_RESPONSE,
    INTERRUPTED,
    NON_RETRYABLE,
    UNKNOWN_PROVIDER_FAILURE
}
