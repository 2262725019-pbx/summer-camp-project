package com.summercamp.project.agent;

import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public final class AgentOrchestrator {
    private static final String SAFE_FAILURE_REPLY = "未能完成本次健康生活规划，请稍后重试或调整目标。";
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentOrchestrator.class);

    private final AgentPlanner planner;
    private final AgentExecutor executor;
    private final Supplier<AgentRunMetricsCollector> metricsCollectorFactory;

    @Autowired
    public AgentOrchestrator(AgentPlanner planner, AgentExecutor executor) {
        this(planner, executor, AgentRunMetricsCollector::new);
    }

    AgentOrchestrator(
            AgentPlanner planner,
            AgentExecutor executor,
            Supplier<AgentRunMetricsCollector> metricsCollectorFactory
    ) {
        this.planner = Objects.requireNonNull(planner, "planner must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.metricsCollectorFactory = Objects.requireNonNull(
                metricsCollectorFactory, "metricsCollectorFactory must not be null");
    }

    public AgentRunResult run(AgentRunRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        return run(request, createCollector());
    }

    AgentRunResult run(AgentRunRequest request, AgentRunMetricsCollector collector) {
        Objects.requireNonNull(request, "request must not be null");
        long runStartedAt = System.nanoTime();
        AgentRunMetrics metrics = AgentRunMetrics.observe(
                Objects.requireNonNull(collector, "collector must not be null"));
        return run(request, metrics, runStartedAt);
    }

    private AgentRunResult run(
            AgentRunRequest request,
            AgentRunMetrics metrics,
            long runStartedAt
    ) {
        AgentPlan plan;
        long plannerStartedAt = System.nanoTime();
        try {
            plan = planner.plan(request.goal(), metrics);
        } catch (RuntimeException exception) {
            metrics.recordPlannerDuration(System.nanoTime() - plannerStartedAt);
            return finish(new AgentRunResult(
                    AgentRunResult.Status.FAILED, SAFE_FAILURE_REPLY, null, null),
                    metrics, runStartedAt);
        }
        metrics.recordPlannerDuration(System.nanoTime() - plannerStartedAt);
        metrics.recordPlanStepCount(plan.steps().size());

        AgentState state;
        long executorStartedAt = System.nanoTime();
        try {
            state = executor.execute(
                    request.userId(),
                    request.goal(),
                    request.history(),
                    request.voiceMessage(),
                    plan,
                    metrics
            );
        } catch (RuntimeException exception) {
            metrics.recordExecutorDuration(System.nanoTime() - executorStartedAt);
            return finish(new AgentRunResult(
                    AgentRunResult.Status.FAILED, SAFE_FAILURE_REPLY, plan, null),
                    metrics, runStartedAt);
        }
        metrics.recordExecutorDuration(System.nanoTime() - executorStartedAt);

        AgentObservation waiting = plan.steps().stream()
                .map(step -> state.findObservation(step.id()).orElse(null))
                .filter(Objects::nonNull)
                .filter(this::needsUserInput)
                .findFirst()
                .orElse(null);
        if (waiting != null) {
            return finish(new AgentRunResult(
                    AgentRunResult.Status.NEEDS_USER_INPUT,
                    waiting.summary().isBlank() ? "请补充必要信息后继续。" : waiting.summary(),
                    plan,
                    state
            ), metrics, runStartedAt);
        }

        AgentStep synthesis = plan.steps().stream()
                .filter(step -> step.action() == AgentAction.SYNTHESIZE)
                .findFirst()
                .orElse(null);
        if (synthesis != null && state.statusOf(synthesis.id()) == AgentStepStatus.COMPLETED) {
            AgentObservation result = state.findObservation(synthesis.id()).orElse(null);
            if (result != null && result.success()) {
                return finish(new AgentRunResult(
                        AgentRunResult.Status.COMPLETED, result.summary(), plan, state),
                        metrics, runStartedAt);
            }
        }
        return finish(new AgentRunResult(
                AgentRunResult.Status.FAILED, SAFE_FAILURE_REPLY, plan, state),
                metrics, runStartedAt);
    }

    private AgentRunResult finish(
            AgentRunResult result,
            AgentRunMetrics metrics,
            long runStartedAt
    ) {
        metrics.recordAgentRunDuration(System.nanoTime() - runStartedAt);
        AgentRunMetricsSnapshot snapshot = metrics.snapshot();
        switch (result.status()) {
            case COMPLETED -> LOGGER.info("Agent 执行完成：status=COMPLETED");
            case NEEDS_USER_INPUT -> LOGGER.info("Agent 执行结束：status=NEEDS_USER_INPUT");
            case FAILED -> LOGGER.warn("Agent 执行结束：status=FAILED");
        }
        LOGGER.info(
                "Agent performance: status={}, durationMs={}, plannerMs={}, executorMs={}, "
                        + "synthesisMs={}, planSteps={}, executedSteps={}, completedSteps={}, "
                        + "failedSteps={}, skippedSteps={}, llmRequests={}, toolCalls={}, "
                        + "weatherCalls={}, weatherReuseEligible={}, weatherReuseApplied={}, "
                        + "ragQueries={}, skillCalls={}, exerciseSkillDurationMs={}, "
                        + "exerciseSkillLlmRequests={}, exercisePrimaryProviderRequests={}, "
                        + "exerciseFallbackProviderRequests={}, synthesisContextChars={}, "
                        + "synthesisInstructionChars={}, synthesisMetadataChars={}, "
                        + "synthesisOriginalGoalChars={}, synthesisDatetimeChars={}, "
                        + "synthesisWeatherChars={}, synthesisExerciseChars={}, "
                        + "synthesisMealChars={}, synthesisRagChars={}, synthesisTodoChars={}, "
                        + "synthesisValidateChars={}, synthesisCalculateChars={}",
                result.status(),
                snapshot.agentRunDurationMs(),
                snapshot.plannerDurationMs(),
                snapshot.executorDurationMs(),
                snapshot.synthesisDurationMs(),
                snapshot.planStepCount(),
                snapshot.executedStepCount(),
                snapshot.completedStepCount(),
                snapshot.failedStepCount(),
                snapshot.skippedStepCount(),
                snapshot.llmRequestCount(),
                snapshot.toolCallCount(),
                snapshot.weatherToolCallCount(),
                snapshot.weatherReuseEligibleCount(),
                snapshot.weatherReuseAppliedCount(),
                snapshot.ragQueryCount(),
                snapshot.skillCallCount(),
                snapshot.exerciseSkillDurationMs(),
                snapshot.exerciseSkillLlmRequestCount(),
                snapshot.exercisePrimaryProviderRequestCount(),
                snapshot.exerciseFallbackProviderRequestCount(),
                snapshot.synthesisContextChars(),
                snapshot.synthesisInstructionChars(),
                snapshot.synthesisMetadataChars(),
                snapshot.synthesisOriginalGoalChars(),
                snapshot.synthesisDatetimeChars(),
                snapshot.synthesisWeatherChars(),
                snapshot.synthesisExerciseChars(),
                snapshot.synthesisMealChars(),
                snapshot.synthesisRagChars(),
                snapshot.synthesisTodoChars(),
                snapshot.synthesisValidateChars(),
                snapshot.synthesisCalculateChars());
        return result;
    }

    private AgentRunMetricsCollector createCollector() {
        try {
            return Objects.requireNonNull(metricsCollectorFactory.get());
        } catch (RuntimeException exception) {
            LOGGER.warn("Agent performance collector creation failed: type={}",
                    exception.getClass().getSimpleName());
            return new AgentRunMetricsCollector();
        }
    }

    private boolean needsUserInput(AgentObservation observation) {
        return ValidateActionHandler.NEEDS_USER_INPUT.equals(observation.structuredData().get("code"))
                && Boolean.parseBoolean(observation.structuredData().getOrDefault("recoverable", "false"));
    }
}
