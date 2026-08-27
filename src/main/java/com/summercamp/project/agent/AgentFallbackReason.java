package com.summercamp.project.agent;

/** Safe, content-free reason codes for application-controlled deterministic fallbacks. */
public enum AgentFallbackReason {
    NONE,
    RATE_LIMIT,
    TIMEOUT,
    CONNECTIVITY,
    SERVER_ERROR,
    INVALID_PLAN_AFTER_REPAIR,
    INVALID_SYNTHESIS_AFTER_REPAIR
}
