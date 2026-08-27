package com.summercamp.project.agent;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Repairs only the mechanical VALIDATE/SYNTHESIZE closure of an otherwise valid plan. */
public final class AgentPlanClosureNormalizer {
    private static final Set<AgentPlanErrorCode> ALLOWED_ERRORS = EnumSet.of(
            AgentPlanErrorCode.SYNTHESIZE_NOT_LAST,
            AgentPlanErrorCode.SYNTHESIZE_VALIDATION_DEPENDENCY_INVALID,
            AgentPlanErrorCode.VALIDATE_BRANCH_COVERAGE_INVALID);

    Optional<AgentPlan> normalize(
            AgentPlan plan,
            List<AgentPlanValidationIssue> issues
    ) {
        if (plan == null || issues == null || issues.isEmpty()) {
            return Optional.empty();
        }
        if (issues.stream().anyMatch(issue ->
                issue.source() != AgentPlanValidationSource.PLAN_VALIDATOR)) {
            return Optional.empty();
        }
        Set<AgentPlanErrorCode> codes = issues.stream()
                .map(AgentPlanValidationIssue::code)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!ALLOWED_ERRORS.containsAll(codes)) {
            return Optional.empty();
        }
        List<AgentStep> validations = steps(plan, AgentAction.VALIDATE);
        List<AgentStep> syntheses = steps(plan, AgentAction.SYNTHESIZE);
        if (validations.size() != 1 || syntheses.size() != 1) {
            return Optional.empty();
        }

        List<AgentStep> business = plan.steps().stream()
                .filter(step -> step.action() != AgentAction.VALIDATE
                        && step.action() != AgentAction.SYNTHESIZE)
                .toList();
        if (business.isEmpty()) {
            return Optional.empty();
        }
        AgentStep validation = validations.getFirst();
        AgentStep synthesis = syntheses.getFirst();
        AgentStep normalizedValidation = copyWithDependencies(
                validation, business.stream().map(AgentStep::id).toList());
        AgentStep normalizedSynthesis = copyWithDependencies(
                synthesis, List.of(validation.id()));
        List<AgentStep> normalized = new ArrayList<>(business.size() + 2);
        normalized.addAll(business);
        normalized.add(normalizedValidation);
        normalized.add(normalizedSynthesis);
        return Optional.of(new AgentPlan(plan.goal(), normalized));
    }

    private List<AgentStep> steps(AgentPlan plan, AgentAction action) {
        return plan.steps().stream().filter(step -> step.action() == action).toList();
    }

    private AgentStep copyWithDependencies(AgentStep step, List<String> dependencies) {
        return new AgentStep(
                step.id(),
                step.action(),
                step.description(),
                step.reason(),
                dependencies,
                step.inputs(),
                step.status());
    }
}
