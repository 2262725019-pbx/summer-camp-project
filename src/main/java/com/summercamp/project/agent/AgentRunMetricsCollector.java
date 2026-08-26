package com.summercamp.project.agent;

import java.util.concurrent.atomic.AtomicLong;

/** Thread-safe mutable collector owned by exactly one Agent run. */
public class AgentRunMetricsCollector {
    private final AtomicLong agentRunDurationNanos = new AtomicLong();
    private final AtomicLong plannerDurationNanos = new AtomicLong();
    private final AtomicLong executorDurationNanos = new AtomicLong();
    private final AtomicLong synthesisDurationNanos = new AtomicLong();
    private final AtomicLong planStepCount = new AtomicLong();
    private final AtomicLong executedStepCount = new AtomicLong();
    private final AtomicLong completedStepCount = new AtomicLong();
    private final AtomicLong failedStepCount = new AtomicLong();
    private final AtomicLong skippedStepCount = new AtomicLong();
    private final AtomicLong llmRequestCount = new AtomicLong();
    private final AtomicLong planningLlmRequestCount = new AtomicLong();
    private final AtomicLong synthesisLlmRequestCount = new AtomicLong();
    private final AtomicLong skillLlmRequestCount = new AtomicLong();
    private final AtomicLong toolCallCount = new AtomicLong();
    private final AtomicLong weatherToolCallCount = new AtomicLong();
    private final AtomicLong weatherReuseEligibleCount = new AtomicLong();
    private final AtomicLong weatherReuseAppliedCount = new AtomicLong();
    private final AtomicLong calculateToolCallCount = new AtomicLong();
    private final AtomicLong todoToolCallCount = new AtomicLong();
    private final AtomicLong dateTimeToolCallCount = new AtomicLong();
    private final AtomicLong ragQueryCount = new AtomicLong();
    private final AtomicLong skillCallCount = new AtomicLong();
    private final AtomicLong exerciseSkillCallCount = new AtomicLong();
    private final AtomicLong exerciseSkillDurationNanos = new AtomicLong();
    private final AtomicLong exerciseSkillLlmRequestCount = new AtomicLong();
    private final AtomicLong exercisePrimaryProviderRequestCount = new AtomicLong();
    private final AtomicLong exerciseFallbackProviderRequestCount = new AtomicLong();
    private final AtomicLong mealSkillCallCount = new AtomicLong();
    private final AtomicLong synthesisContextChars = new AtomicLong();
    private final AtomicLong synthesisInstructionChars = new AtomicLong();
    private final AtomicLong synthesisMetadataChars = new AtomicLong();
    private final AtomicLong synthesisOriginalGoalChars = new AtomicLong();
    private final AtomicLong synthesisDatetimeChars = new AtomicLong();
    private final AtomicLong synthesisWeatherChars = new AtomicLong();
    private final AtomicLong synthesisExerciseChars = new AtomicLong();
    private final AtomicLong synthesisMealChars = new AtomicLong();
    private final AtomicLong synthesisRagChars = new AtomicLong();
    private final AtomicLong synthesisTodoChars = new AtomicLong();
    private final AtomicLong synthesisValidateChars = new AtomicLong();
    private final AtomicLong synthesisCalculateChars = new AtomicLong();
    private final AtomicLong plannerInstructionChars = new AtomicLong();
    private final AtomicLong plannerGoalChars = new AtomicLong();
    private final AtomicLong promptChars = new AtomicLong();
    private final AtomicLong responseChars = new AtomicLong();
    private final AtomicLong contextChars = new AtomicLong();

    public void recordAgentRunDuration(long nanos) {
        agentRunDurationNanos.set(nonNegative(nanos));
    }

    public void recordPlannerDuration(long nanos) {
        plannerDurationNanos.addAndGet(nonNegative(nanos));
    }

    public void recordExecutorDuration(long nanos) {
        executorDurationNanos.addAndGet(nonNegative(nanos));
    }

    public void recordSynthesisDuration(long nanos) {
        synthesisDurationNanos.addAndGet(nonNegative(nanos));
    }

    public void recordPlanStepCount(long count) {
        planStepCount.set(nonNegative(count));
    }

    public void recordExecutedStep() {
        executedStepCount.incrementAndGet();
    }

    public void recordCompletedStep() {
        completedStepCount.incrementAndGet();
    }

    public void recordFailedStep() {
        failedStepCount.incrementAndGet();
    }

    public void recordSkippedStep() {
        skippedStepCount.incrementAndGet();
    }

    public void recordLlmRequest(AgentRunMetrics.LlmPhase phase, long requestChars, long inputChars) {
        llmRequestCount.incrementAndGet();
        switch (phase) {
            case PLANNING -> planningLlmRequestCount.incrementAndGet();
            case SYNTHESIS -> synthesisLlmRequestCount.incrementAndGet();
            case SKILL -> skillLlmRequestCount.incrementAndGet();
            case EXERCISE_SKILL -> {
                skillLlmRequestCount.incrementAndGet();
                exerciseSkillLlmRequestCount.incrementAndGet();
            }
            case OTHER -> {
                // The total still represents a real provider request made during this Agent run.
            }
        }
        promptChars.addAndGet(nonNegative(requestChars));
        contextChars.addAndGet(nonNegative(inputChars));
    }

    public void recordLlmResponse(long chars) {
        responseChars.addAndGet(nonNegative(chars));
    }

    public void recordToolCall(String toolName) {
        toolCallCount.incrementAndGet();
        if (toolName == null) {
            return;
        }
        switch (toolName) {
            case "get_weather" -> weatherToolCallCount.incrementAndGet();
            case "calculate" -> calculateToolCallCount.incrementAndGet();
            case "add_todo" -> todoToolCallCount.incrementAndGet();
            case "get_current_datetime" -> dateTimeToolCallCount.incrementAndGet();
            default -> {
                // Per-tool metrics are intentionally limited to the current Agent capabilities.
            }
        }
    }

    public void recordRagQuery() {
        ragQueryCount.incrementAndGet();
    }

    public void recordWeatherReuseEligible() {
        weatherReuseEligibleCount.incrementAndGet();
    }

    public void recordWeatherReuseApplied() {
        weatherReuseAppliedCount.incrementAndGet();
    }

    public void recordSkillCall(AgentAction action) {
        skillCallCount.incrementAndGet();
        if (action == AgentAction.RUN_EXERCISE_SKILL) {
            exerciseSkillCallCount.incrementAndGet();
        } else if (action == AgentAction.RUN_MEAL_SKILL) {
            mealSkillCallCount.incrementAndGet();
        }
    }

    public void recordSkillDuration(AgentAction action, long nanos) {
        if (action == AgentAction.RUN_EXERCISE_SKILL) {
            exerciseSkillDurationNanos.addAndGet(nonNegative(nanos));
        }
    }

    public void recordExerciseProviderRequest(boolean fallback) {
        if (fallback) {
            exerciseFallbackProviderRequestCount.incrementAndGet();
        } else {
            exercisePrimaryProviderRequestCount.incrementAndGet();
        }
    }

    public void recordSynthesisContextChars(long chars) {
        synthesisContextChars.addAndGet(nonNegative(chars));
    }

    public void recordSynthesisContext(AgentSynthesisContextBuilder.Breakdown breakdown) {
        synthesisContextChars.addAndGet(nonNegative(breakdown.totalChars()));
        synthesisMetadataChars.addAndGet(nonNegative(breakdown.metadataChars()));
        synthesisOriginalGoalChars.addAndGet(nonNegative(breakdown.originalGoalChars()));
        synthesisDatetimeChars.addAndGet(nonNegative(breakdown.datetimeChars()));
        synthesisWeatherChars.addAndGet(nonNegative(breakdown.weatherChars()));
        synthesisExerciseChars.addAndGet(nonNegative(breakdown.exerciseChars()));
        synthesisMealChars.addAndGet(nonNegative(breakdown.mealChars()));
        synthesisRagChars.addAndGet(nonNegative(breakdown.ragChars()));
        synthesisTodoChars.addAndGet(nonNegative(breakdown.todoChars()));
        synthesisValidateChars.addAndGet(nonNegative(breakdown.validateChars()));
        synthesisCalculateChars.addAndGet(nonNegative(breakdown.calculateChars()));
    }

    public void recordSynthesisInstructionChars(long chars) {
        synthesisInstructionChars.addAndGet(nonNegative(chars));
    }

    public void recordPlannerInputChars(long goalChars, long instructionChars) {
        plannerGoalChars.addAndGet(nonNegative(goalChars));
        plannerInstructionChars.addAndGet(nonNegative(instructionChars));
    }

    public AgentRunMetricsSnapshot snapshot() {
        return new AgentRunMetricsSnapshot(
                millis(agentRunDurationNanos),
                millis(plannerDurationNanos),
                millis(executorDurationNanos),
                millis(synthesisDurationNanos),
                planStepCount.get(),
                executedStepCount.get(),
                completedStepCount.get(),
                failedStepCount.get(),
                skippedStepCount.get(),
                llmRequestCount.get(),
                planningLlmRequestCount.get(),
                synthesisLlmRequestCount.get(),
                skillLlmRequestCount.get(),
                toolCallCount.get(),
                weatherToolCallCount.get(),
                weatherReuseEligibleCount.get(),
                weatherReuseAppliedCount.get(),
                calculateToolCallCount.get(),
                todoToolCallCount.get(),
                dateTimeToolCallCount.get(),
                ragQueryCount.get(),
                skillCallCount.get(),
                exerciseSkillCallCount.get(),
                millis(exerciseSkillDurationNanos),
                exerciseSkillLlmRequestCount.get(),
                exercisePrimaryProviderRequestCount.get(),
                exerciseFallbackProviderRequestCount.get(),
                mealSkillCallCount.get(),
                synthesisContextChars.get(),
                synthesisInstructionChars.get(),
                synthesisMetadataChars.get(),
                synthesisOriginalGoalChars.get(),
                synthesisDatetimeChars.get(),
                synthesisWeatherChars.get(),
                synthesisExerciseChars.get(),
                synthesisMealChars.get(),
                synthesisRagChars.get(),
                synthesisTodoChars.get(),
                synthesisValidateChars.get(),
                synthesisCalculateChars.get(),
                plannerInstructionChars.get(),
                plannerGoalChars.get(),
                promptChars.get(),
                responseChars.get(),
                contextChars.get());
    }

    private long millis(AtomicLong nanos) {
        return nanos.get() / 1_000_000;
    }

    private long nonNegative(long value) {
        return Math.max(0, value);
    }
}
