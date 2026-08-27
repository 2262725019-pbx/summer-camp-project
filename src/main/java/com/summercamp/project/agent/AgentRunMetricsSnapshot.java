package com.summercamp.project.agent;

/** Immutable, content-free performance measurements for one Agent run. */
public record AgentRunMetricsSnapshot(
        long agentRunDurationMs,
        long plannerDurationMs,
        long executorDurationMs,
        long synthesisDurationMs,
        long planStepCount,
        long executedStepCount,
        long completedStepCount,
        long failedStepCount,
        long skippedStepCount,
        long llmRequestCount,
        long planningLlmRequestCount,
        long synthesisLlmRequestCount,
        long skillLlmRequestCount,
        long toolCallCount,
        long weatherToolCallCount,
        long weatherReuseEligibleCount,
        long weatherReuseAppliedCount,
        long calculateToolCallCount,
        long todoToolCallCount,
        long dateTimeToolCallCount,
        long ragQueryCount,
        long skillCallCount,
        long exerciseSkillCallCount,
        long exerciseSkillDurationMs,
        long exerciseSkillLlmRequestCount,
        long exercisePrimaryProviderRequestCount,
        long exerciseFallbackProviderRequestCount,
        long mealSkillCallCount,
        long synthesisContextChars,
        long synthesisInstructionChars,
        long synthesisMetadataChars,
        long synthesisOriginalGoalChars,
        long synthesisDatetimeChars,
        long synthesisWeatherChars,
        long synthesisExerciseChars,
        long synthesisMealChars,
        long synthesisRagChars,
        long synthesisTodoChars,
        long synthesisValidateChars,
        long synthesisCalculateChars,
        long plannerInstructionChars,
        long plannerGoalChars,
        long promptChars,
        long responseChars,
        long contextChars,
        long agentResumeCount,
        long resumeAttemptCount,
        long reusedCompletedStepCount,
        long executedAfterResumeStepCount,
        long finalValidationAttemptCount,
        long finalValidationFailureCount,
        long synthesisRepairTriggeredCount,
        long synthesisRepairSucceededCount,
        long synthesisRepairInstructionChars,
        long plannerClosureNormalizedCount,
        long deterministicPlannerFallbackCount,
        long deterministicExerciseFallbackCount,
        long deterministicSynthesisFallbackCount,
        int deterministicPlannerFallbackReasonCode,
        int deterministicExerciseFallbackReasonCode,
        int deterministicSynthesisFallbackReasonCode
) {
    public static AgentRunMetricsSnapshot empty() {
        return new AgentRunMetricsSnapshot(
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public AgentFallbackReason deterministicPlannerFallbackReason() {
        return reason(deterministicPlannerFallbackReasonCode);
    }

    public AgentFallbackReason deterministicExerciseFallbackReason() {
        return reason(deterministicExerciseFallbackReasonCode);
    }

    public AgentFallbackReason deterministicSynthesisFallbackReason() {
        return reason(deterministicSynthesisFallbackReasonCode);
    }

    private static AgentFallbackReason reason(int code) {
        AgentFallbackReason[] values = AgentFallbackReason.values();
        return code >= 0 && code < values.length ? values[code] : AgentFallbackReason.NONE;
    }
}
