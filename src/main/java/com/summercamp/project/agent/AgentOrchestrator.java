package com.summercamp.project.agent;

import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public final class AgentOrchestrator {
    private static final String SAFE_FAILURE_REPLY = "未能完成本次健康生活规划，请稍后重试或调整目标。";

    private final AgentPlanner planner;
    private final AgentExecutor executor;

    public AgentOrchestrator(AgentPlanner planner, AgentExecutor executor) {
        this.planner = Objects.requireNonNull(planner, "planner must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
    }

    public AgentRunResult run(AgentRunRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        AgentPlan plan;
        try {
            plan = planner.plan(request.goal());
        } catch (RuntimeException exception) {
            return new AgentRunResult(AgentRunResult.Status.FAILED, SAFE_FAILURE_REPLY, null, null);
        }

        AgentState state;
        try {
            state = executor.execute(
                    request.userId(),
                    request.goal(),
                    request.history(),
                    request.voiceMessage(),
                    plan
            );
        } catch (RuntimeException exception) {
            return new AgentRunResult(AgentRunResult.Status.FAILED, SAFE_FAILURE_REPLY, plan, null);
        }

        AgentObservation waiting = plan.steps().stream()
                .map(step -> state.findObservation(step.id()).orElse(null))
                .filter(Objects::nonNull)
                .filter(this::needsUserInput)
                .findFirst()
                .orElse(null);
        if (waiting != null) {
            return new AgentRunResult(
                    AgentRunResult.Status.NEEDS_USER_INPUT,
                    waiting.summary().isBlank() ? "请补充必要信息后继续。" : waiting.summary(),
                    plan,
                    state
            );
        }

        AgentStep synthesis = plan.steps().stream()
                .filter(step -> step.action() == AgentAction.SYNTHESIZE)
                .findFirst()
                .orElse(null);
        if (synthesis != null && state.statusOf(synthesis.id()) == AgentStepStatus.COMPLETED) {
            AgentObservation result = state.findObservation(synthesis.id()).orElse(null);
            if (result != null && result.success()) {
                return new AgentRunResult(AgentRunResult.Status.COMPLETED, result.summary(), plan, state);
            }
        }
        return new AgentRunResult(AgentRunResult.Status.FAILED, SAFE_FAILURE_REPLY, plan, state);
    }

    private boolean needsUserInput(AgentObservation observation) {
        return ValidateActionHandler.NEEDS_USER_INPUT.equals(observation.structuredData().get("code"))
                && Boolean.parseBoolean(observation.structuredData().getOrDefault("recoverable", "false"));
    }
}
