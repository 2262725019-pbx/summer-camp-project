package com.summercamp.project.agent;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable in-memory breakpoint for one recoverable Agent run. */
public record AgentRunCheckpoint(
        String originalGoal,
        AgentPlan plan,
        AgentStateSnapshot state,
        String waitingStepId,
        AgentAction waitingAction,
        int resumeAttemptCount,
        Instant createdAt,
        Instant expiresAt,
        boolean originalVoiceMessage
) {

    public AgentRunCheckpoint {
        if (originalGoal == null || originalGoal.isBlank()) {
            throw new IllegalArgumentException("originalGoal must not be blank");
        }
        plan = Objects.requireNonNull(plan, "plan must not be null");
        state = Objects.requireNonNull(state, "state must not be null");
        if (!plan.equals(state.plan())) {
            throw new IllegalArgumentException("checkpoint state must belong to plan");
        }
        if (waitingStepId == null || waitingStepId.isBlank()) {
            throw new IllegalArgumentException("waitingStepId must not be blank");
        }
        waitingAction = Objects.requireNonNull(waitingAction, "waitingAction must not be null");
        if (resumeAttemptCount < 0) {
            throw new IllegalArgumentException("resumeAttemptCount must not be negative");
        }
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt must be after createdAt");
        }
        validateState(plan, state, waitingStepId, waitingAction);
    }

    static Optional<AgentRunCheckpoint> capture(
            String originalGoal,
            AgentRunResult result,
            int resumeAttemptCount,
            Instant createdAt,
            Instant expiresAt,
            boolean originalVoiceMessage
    ) {
        if (result == null
                || result.status() != AgentRunResult.Status.NEEDS_USER_INPUT
                || result.plan() == null
                || result.state() == null) {
            return Optional.empty();
        }
        List<AgentObservation> waiting = result.state().observations().stream()
                .filter(AgentRunCheckpoint::needsUserInput)
                .toList();
        if (waiting.size() != 1) {
            return Optional.empty();
        }
        AgentObservation observation = waiting.getFirst();
        AgentStep step = result.plan().steps().stream()
                .filter(candidate -> candidate.id().equals(observation.stepId()))
                .findFirst()
                .orElse(null);
        if (step == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new AgentRunCheckpoint(
                    originalGoal,
                    result.plan(),
                    AgentStateSnapshot.from(result.state()),
                    step.id(),
                    step.action(),
                    resumeAttemptCount,
                    createdAt,
                    expiresAt,
                    originalVoiceMessage));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    AgentRunCheckpoint refreshExpiry(Instant refreshedExpiresAt) {
        return new AgentRunCheckpoint(
                originalGoal,
                plan,
                state,
                waitingStepId,
                waitingAction,
                resumeAttemptCount,
                createdAt,
                refreshedExpiresAt,
                originalVoiceMessage);
    }

    private static void validateState(
            AgentPlan plan,
            AgentStateSnapshot state,
            String waitingStepId,
            AgentAction waitingAction
    ) {
        AgentStep waitingStep = plan.steps().stream()
                .filter(step -> waitingStepId.equals(step.id()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("waiting step is not in plan"));
        if (waitingStep.action() != waitingAction) {
            throw new IllegalArgumentException("waiting action does not match plan step");
        }
        if (state.statusOf(waitingStepId) != AgentStepStatus.FAILED
                || state.findObservation(waitingStepId).filter(AgentRunCheckpoint::needsUserInput).isEmpty()) {
            throw new IllegalArgumentException("waiting step must be a recoverable failed observation");
        }
        long waitingCount = state.observations().stream()
                .filter(AgentRunCheckpoint::needsUserInput)
                .count();
        if (waitingCount != 1) {
            throw new IllegalArgumentException("checkpoint must contain exactly one waiting step");
        }
        for (AgentStep step : plan.steps()) {
            AgentStepStatus status = state.statusOf(step.id());
            if (status == AgentStepStatus.RUNNING || status == AgentStepStatus.PENDING) {
                throw new IllegalArgumentException("checkpoint cannot contain non-terminal steps");
            }
        }
    }

    static boolean needsUserInput(AgentObservation observation) {
        return ValidateActionHandler.NEEDS_USER_INPUT.equals(
                observation.structuredData().get("code"))
                && Boolean.parseBoolean(
                observation.structuredData().getOrDefault("recoverable", "false"));
    }

    static boolean dependsTransitivelyOn(
            AgentStep step,
            String dependencyId,
            AgentPlan plan
    ) {
        return dependsTransitivelyOn(step, dependencyId, plan, new java.util.HashSet<>());
    }

    private static boolean dependsTransitivelyOn(
            AgentStep step,
            String dependencyId,
            AgentPlan plan,
            java.util.Set<String> visited
    ) {
        if (!visited.add(step.id())) {
            return false;
        }
        for (String direct : step.dependsOn()) {
            if (dependencyId.equals(direct)) {
                return true;
            }
            AgentStep parent = plan.steps().stream()
                    .filter(candidate -> candidate.id().equals(direct))
                    .findFirst()
                    .orElse(null);
            if (parent != null && dependsTransitivelyOn(parent, dependencyId, plan, visited)) {
                return true;
            }
        }
        return false;
    }
}
