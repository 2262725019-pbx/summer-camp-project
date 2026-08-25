package com.summercamp.project.agent;

import com.summercamp.project.llm.ChatMessage;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public final class AgentExecutor {
    public static final int MAX_EXECUTED_STEPS = AgentPlanValidator.MAX_STEPS;

    private final AgentActionHandlerRegistry handlerRegistry;

    public AgentExecutor(AgentActionHandlerRegistry handlerRegistry) {
        this.handlerRegistry = Objects.requireNonNull(handlerRegistry, "handlerRegistry must not be null");
    }

    public AgentState execute(AgentPlan plan) {
        Objects.requireNonNull(plan, "plan must not be null");
        return execute(plan.goal(), plan);
    }

    public AgentState execute(String originalGoal, AgentPlan plan) {
        return execute("", originalGoal, List.of(), false, plan);
    }

    public AgentState execute(
            String userId,
            String originalGoal,
            List<ChatMessage> history,
            boolean voiceMessage,
            AgentPlan plan
    ) {
        AgentState state = new AgentState(Objects.requireNonNull(plan, "plan must not be null"));
        AgentExecutionContext context = new AgentExecutionContext(
                userId,
                originalGoal,
                history,
                voiceMessage,
                state,
                plan
        );
        int executedSteps = 0;

        while (state.hasPendingSteps()) {
            skipStepsWithFailedDependencies(plan, state);
            if (!state.hasPendingSteps()) {
                break;
            }

            Optional<AgentStep> readyStep = findFirstReadyStep(plan, state);
            if (readyStep.isEmpty()) {
                AgentExecutionFailureReason reason = hasUnknownDependency(plan, state)
                        ? AgentExecutionFailureReason.INVALID_RUNTIME_DEPENDENCY_STATE
                        : AgentExecutionFailureReason.NO_PROGRESS;
                throw new AgentExecutionException(
                        reason,
                        "Agent execution cannot make progress while pending steps remain"
                );
            }
            if (executedSteps >= MAX_EXECUTED_STEPS) {
                throw new AgentExecutionException(
                        AgentExecutionFailureReason.MAX_EXECUTED_STEPS,
                        "Agent execution exceeded maximum executed steps: " + MAX_EXECUTED_STEPS
                );
            }

            executeStep(readyStep.orElseThrow(), context);
            executedSteps++;
        }

        return state;
    }

    private void executeStep(AgentStep step, AgentExecutionContext context) {
        AgentActionHandler handler;
        try {
            handler = handlerRegistry.find(step.action());
        } catch (AgentActionHandlerNotFoundException exception) {
            throw new AgentExecutionException(
                    AgentExecutionFailureReason.MISSING_HANDLER,
                    "Cannot execute step " + step.id() + ": " + exception.getMessage(),
                    exception
            );
        }

        AgentState state = context.mutableState();
        state.markRunning(step.id());
        AgentObservation observation;
        try {
            observation = normalizeObservation(step, handler.execute(step, context));
        } catch (RuntimeException exception) {
            observation = new AgentObservation(
                    step.id(),
                    false,
                    "Handler execution failed: " + exception.getClass().getSimpleName()
            );
        }
        state.recordObservation(observation);
    }

    private AgentObservation normalizeObservation(AgentStep step, AgentObservation observation) {
        if (observation == null) {
            return new AgentObservation(step.id(), false, "Handler returned no observation");
        }
        if (!step.id().equals(observation.stepId())) {
            return new AgentObservation(
                    step.id(),
                    false,
                    "Handler returned an observation for a different step"
            );
        }
        return observation;
    }

    private void skipStepsWithFailedDependencies(AgentPlan plan, AgentState state) {
        boolean changed;
        do {
            changed = false;
            for (AgentStep step : plan.steps()) {
                if (state.statusOf(step.id()) != AgentStepStatus.PENDING) {
                    continue;
                }
                for (String dependencyId : step.dependsOn()) {
                    Optional<AgentStepStatus> dependencyStatus = state.findStatus(dependencyId);
                    if (dependencyStatus.isPresent()
                            && isFailedOrSkipped(dependencyStatus.orElseThrow())) {
                        state.markSkipped(
                                step.id(),
                                "Skipped because dependency " + dependencyId
                                        + " ended as " + dependencyStatus.orElseThrow()
                        );
                        changed = true;
                        break;
                    }
                }
            }
        } while (changed);
    }

    private Optional<AgentStep> findFirstReadyStep(AgentPlan plan, AgentState state) {
        return plan.steps().stream()
                .filter(step -> state.statusOf(step.id()) == AgentStepStatus.PENDING)
                .filter(step -> step.dependsOn().stream()
                        .allMatch(dependencyId -> state.findStatus(dependencyId)
                                .map(status -> status == AgentStepStatus.COMPLETED)
                                .orElse(false)))
                .findFirst();
    }

    private boolean hasUnknownDependency(AgentPlan plan, AgentState state) {
        return plan.steps().stream()
                .filter(step -> state.statusOf(step.id()) == AgentStepStatus.PENDING)
                .flatMap(step -> step.dependsOn().stream())
                .anyMatch(dependencyId -> state.findStatus(dependencyId).isEmpty());
    }

    private boolean isFailedOrSkipped(AgentStepStatus status) {
        return status == AgentStepStatus.FAILED || status == AgentStepStatus.SKIPPED;
    }
}
