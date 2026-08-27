package com.summercamp.project.agent;

import java.util.Objects;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Explicit run-scoped facade that isolates all metrics failures from business execution. */
public final class AgentRunMetrics {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentRunMetrics.class);
    private static final AgentRunMetrics UNOBSERVED = new AgentRunMetrics(null, LlmPhase.OTHER);

    private final AgentRunMetricsCollector collector;
    private final LlmPhase llmPhase;

    private AgentRunMetrics(AgentRunMetricsCollector collector, LlmPhase llmPhase) {
        this.collector = collector;
        this.llmPhase = Objects.requireNonNull(llmPhase, "llmPhase must not be null");
    }

    public static AgentRunMetrics observe(AgentRunMetricsCollector collector) {
        return new AgentRunMetrics(
                Objects.requireNonNull(collector, "collector must not be null"),
                LlmPhase.OTHER);
    }

    public static AgentRunMetrics unobserved() {
        return UNOBSERVED;
    }

    public AgentRunMetrics withLlmPhase(LlmPhase phase) {
        if (collector == null) {
            return UNOBSERVED;
        }
        return new AgentRunMetrics(collector, Objects.requireNonNull(phase, "phase must not be null"));
    }

    public LlmPhase llmPhase() {
        return llmPhase;
    }

    public void recordAgentRunDuration(long nanos) {
        safely(current -> current.recordAgentRunDuration(nanos));
    }

    public void recordPlannerDuration(long nanos) {
        safely(current -> current.recordPlannerDuration(nanos));
    }

    public void recordExecutorDuration(long nanos) {
        safely(current -> current.recordExecutorDuration(nanos));
    }

    public void recordPlanStepCount(long count) {
        safely(current -> current.recordPlanStepCount(count));
    }

    public void recordExecutedStep() {
        safely(AgentRunMetricsCollector::recordExecutedStep);
    }

    public void recordCompletedStep() {
        safely(AgentRunMetricsCollector::recordCompletedStep);
    }

    public void recordFailedStep() {
        safely(AgentRunMetricsCollector::recordFailedStep);
    }

    public void recordSkippedStep() {
        safely(AgentRunMetricsCollector::recordSkippedStep);
    }

    public void recordAgentResume(long attempt, long reusedCompletedSteps) {
        safely(current -> current.recordAgentResume(attempt, reusedCompletedSteps));
    }

    public void recordExecutedAfterResumeStep() {
        safely(AgentRunMetricsCollector::recordExecutedAfterResumeStep);
    }

    public void recordSynthesisDuration(long nanos) {
        safely(current -> current.recordSynthesisDuration(nanos));
    }

    public void recordSynthesisContextChars(long chars) {
        safely(current -> current.recordSynthesisContextChars(chars));
    }

    public void recordSynthesisContext(AgentSynthesisContextBuilder.Breakdown breakdown) {
        safely(current -> current.recordSynthesisContext(breakdown));
    }

    public void recordSynthesisInstructionChars(long chars) {
        safely(current -> current.recordSynthesisInstructionChars(chars));
    }

    public void recordFinalValidationAttempt(boolean valid) {
        safely(current -> current.recordFinalValidationAttempt(valid));
    }

    public void recordSynthesisRepairTriggered(long instructionChars) {
        safely(current -> current.recordSynthesisRepairTriggered(instructionChars));
    }

    public void recordSynthesisRepairSucceeded() {
        safely(AgentRunMetricsCollector::recordSynthesisRepairSucceeded);
    }

    public void recordPlannerClosureNormalized() {
        safely(AgentRunMetricsCollector::recordPlannerClosureNormalized);
    }

    public void recordDeterministicPlannerFallback(AgentFallbackReason reason) {
        safely(current -> current.recordDeterministicPlannerFallback(reason));
    }

    public void recordDeterministicExerciseFallback(AgentFallbackReason reason) {
        safely(current -> current.recordDeterministicExerciseFallback(reason));
    }

    public void recordDeterministicSynthesisFallback(AgentFallbackReason reason) {
        safely(current -> current.recordDeterministicSynthesisFallback(reason));
    }

    public void recordPlannerInputChars(long goalChars, long instructionChars) {
        safely(current -> current.recordPlannerInputChars(goalChars, instructionChars));
    }

    public void recordProviderRequest(long requestChars, long inputChars) {
        safely(current -> current.recordLlmRequest(llmPhase, requestChars, inputChars));
    }

    public void recordProviderResponse(long chars) {
        safely(current -> current.recordLlmResponse(chars));
    }

    public void recordToolCall(String toolName) {
        safely(current -> current.recordToolCall(toolName));
    }

    public void recordWeatherReuseEligible() {
        safely(AgentRunMetricsCollector::recordWeatherReuseEligible);
    }

    public void recordWeatherReuseApplied() {
        safely(AgentRunMetricsCollector::recordWeatherReuseApplied);
    }

    public void recordRagQuery() {
        safely(AgentRunMetricsCollector::recordRagQuery);
    }

    public void recordSkillCall(AgentAction action) {
        safely(current -> current.recordSkillCall(action));
    }

    public void recordSkillDuration(AgentAction action, long nanos) {
        safely(current -> current.recordSkillDuration(action, nanos));
    }

    public void recordExerciseProviderRequest(boolean fallback) {
        safely(current -> current.recordExerciseProviderRequest(fallback));
    }

    public AgentRunMetricsSnapshot snapshot() {
        if (collector == null) {
            return AgentRunMetricsSnapshot.empty();
        }
        try {
            return collector.snapshot();
        } catch (RuntimeException exception) {
            logFailure("snapshot", exception);
            return AgentRunMetricsSnapshot.empty();
        }
    }

    private void safely(Consumer<AgentRunMetricsCollector> operation) {
        if (collector == null) {
            return;
        }
        try {
            operation.accept(collector);
        } catch (RuntimeException exception) {
            logFailure("collection", exception);
        }
    }

    private void logFailure(String operation, RuntimeException exception) {
        LOGGER.warn("Agent performance metric {} failed: type={}",
                operation, exception.getClass().getSimpleName());
    }

    public enum LlmPhase {
        PLANNING,
        SYNTHESIS,
        SKILL,
        EXERCISE_SKILL,
        OTHER
    }
}
