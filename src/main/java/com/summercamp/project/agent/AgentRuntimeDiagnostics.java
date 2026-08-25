package com.summercamp.project.agent;

import java.util.Objects;
import java.util.regex.Pattern;

final class AgentRuntimeDiagnostics {
    static final String HANDLER_FAILURE = "HANDLER_FAILURE";
    static final String MISSING_HANDLER = "MISSING_HANDLER";
    static final String UPSTREAM_FAILED = "UPSTREAM_FAILED";
    static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    static final String SYNTHESIS_FAILED = "SYNTHESIS_FAILED";
    static final String UNKNOWN_RUNTIME_FAILURE = "UNKNOWN_RUNTIME_FAILURE";

    private static final Pattern SAFE_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    private AgentRuntimeDiagnostics() {
    }

    static String stepStarted(AgentAction action) {
        return "Agent step 开始：action=" + requireAction(action);
    }

    static String stepCompleted(AgentAction action) {
        return "Agent step 完成：action=" + requireAction(action) + ", status=COMPLETED";
    }

    static String stepFailed(AgentAction action, AgentObservation observation) {
        Objects.requireNonNull(observation, "observation must not be null");
        return stepFailed(action, failureCode(action, observation), recoverable(observation));
    }

    static String stepFailed(AgentAction action, String code, boolean recoverable) {
        return "Agent step 失败：action=" + requireAction(action)
                + ", code=" + safeCode(code, UNKNOWN_RUNTIME_FAILURE)
                + ", recoverable=" + recoverable;
    }

    static String stepSkipped(AgentAction action) {
        return "Agent step 跳过：action=" + requireAction(action)
                + ", reason=" + UPSTREAM_FAILED;
    }

    private static String failureCode(AgentAction action, AgentObservation observation) {
        String providedCode = observation.structuredData().get("code");
        if (providedCode != null && !providedCode.isBlank()) {
            return safeCode(providedCode, UNKNOWN_RUNTIME_FAILURE);
        }
        return switch (requireAction(action)) {
            case VALIDATE -> VALIDATION_FAILED;
            case SYNTHESIZE -> SYNTHESIS_FAILED;
            default -> HANDLER_FAILURE;
        };
    }

    private static boolean recoverable(AgentObservation observation) {
        return Boolean.parseBoolean(observation.structuredData().getOrDefault("recoverable", "false"));
    }

    private static String safeCode(String code, String fallback) {
        if (code == null || !SAFE_CODE.matcher(code).matches()) {
            return fallback;
        }
        return code;
    }

    private static AgentAction requireAction(AgentAction action) {
        return Objects.requireNonNull(action, "action must not be null");
    }
}
