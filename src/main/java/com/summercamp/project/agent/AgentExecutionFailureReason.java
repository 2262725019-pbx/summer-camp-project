package com.summercamp.project.agent;

public enum AgentExecutionFailureReason {
    MISSING_HANDLER,
    NO_PROGRESS,
    INVALID_RUNTIME_DEPENDENCY_STATE,
    MAX_EXECUTED_STEPS
}
