package com.summercamp.project.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class AgentPlanValidator {
    public static final int MIN_STEPS = 3;
    public static final int MAX_STEPS = 12;
    public static final int MIN_DISTINCT_BUSINESS_TASKS = 3;
    private final AgentActionInputValidator inputValidator = new AgentActionInputValidator();

    public AgentPlanValidationResult validate(AgentPlan plan) {
        List<String> errors = new ArrayList<>();
        if (plan == null) {
            errors.add("Plan must not be null");
            return invalid(errors);
        }

        if (plan.goal() == null || plan.goal().isBlank()) {
            errors.add("Goal must not be blank");
        }

        List<AgentStep> steps = plan.steps();
        if (steps.size() < MIN_STEPS || steps.size() > MAX_STEPS) {
            errors.add("Plan must contain between " + MIN_STEPS + " and " + MAX_STEPS + " steps");
        }

        Map<String, AgentStep> stepsById = collectSteps(steps, errors);
        validateStepFieldsAndDependencies(steps, stepsById.keySet(), errors);
        validateBusinessTaskCount(steps, errors);
        validateAcyclicDependencies(stepsById, errors);
        validateClosedLoop(steps, stepsById, errors);

        return errors.isEmpty()
                ? new AgentPlanValidationResult(true, List.of())
                : invalid(errors);
    }

    private Map<String, AgentStep> collectSteps(List<AgentStep> steps, List<String> errors) {
        Map<String, AgentStep> stepsById = new HashMap<>();
        for (int index = 0; index < steps.size(); index++) {
            AgentStep step = steps.get(index);
            if (step == null) {
                errors.add("Step at index " + index + " must not be null");
                continue;
            }
            if (step.id() == null || step.id().isBlank()) {
                errors.add("Step at index " + index + " must have a non-blank id");
            } else if (stepsById.putIfAbsent(step.id(), step) != null) {
                errors.add("Duplicate step id: " + step.id());
            }
        }
        return stepsById;
    }

    private void validateStepFieldsAndDependencies(
            List<AgentStep> steps,
            Set<String> existingStepIds,
            List<String> errors
    ) {
        for (AgentStep step : steps) {
            if (step == null) {
                continue;
            }
            String label = step.id() == null || step.id().isBlank() ? "<unknown>" : step.id();
            if (step.action() == null) {
                errors.add("Step " + label + " must have an AgentAction");
            }
            if (step.description() == null || step.description().isBlank()) {
                errors.add("Step " + label + " must have a non-blank description");
            }
            errors.addAll(inputValidator.validate(step));
            for (String dependency : step.dependsOn()) {
                if (step.id() != null && step.id().equals(dependency)) {
                    errors.add("Step " + label + " must not depend on itself");
                } else if (dependency == null || dependency.isBlank() || !existingStepIds.contains(dependency)) {
                    errors.add("Step " + label + " has unknown dependency: " + dependency);
                }
            }
        }
    }

    private void validateBusinessTaskCount(List<AgentStep> steps, List<String> errors) {
        long distinctBusinessTasks = steps.stream()
                .filter(step -> step != null && isBusinessAction(step.action()))
                .map(AgentStep::action)
                .distinct()
                .count();
        if (distinctBusinessTasks < MIN_DISTINCT_BUSINESS_TASKS) {
            errors.add("Plan must contain at least " + MIN_DISTINCT_BUSINESS_TASKS
                    + " distinct business task actions");
        }
    }

    private void validateAcyclicDependencies(Map<String, AgentStep> stepsById, List<String> errors) {
        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();
        for (String stepId : stepsById.keySet()) {
            if (hasCycle(stepId, stepsById, visited, visiting)) {
                errors.add("Dependency graph must not contain a cycle");
                return;
            }
        }
    }

    private boolean hasCycle(
            String stepId,
            Map<String, AgentStep> stepsById,
            Set<String> visited,
            Set<String> visiting
    ) {
        if (visited.contains(stepId)) {
            return false;
        }
        if (!visiting.add(stepId)) {
            return true;
        }

        AgentStep step = stepsById.get(stepId);
        if (step != null) {
            for (String dependency : step.dependsOn()) {
                if (stepsById.containsKey(dependency)
                        && hasCycle(dependency, stepsById, visited, visiting)) {
                    return true;
                }
            }
        }

        visiting.remove(stepId);
        visited.add(stepId);
        return false;
    }

    private void validateClosedLoop(
            List<AgentStep> steps,
            Map<String, AgentStep> stepsById,
            List<String> errors
    ) {
        List<Integer> validationIndexes = actionIndexes(steps, AgentAction.VALIDATE);
        List<Integer> synthesisIndexes = new ArrayList<>();
        synthesisIndexes.addAll(actionIndexes(steps, AgentAction.SYNTHESIZE));

        if (validationIndexes.size() != 1) {
            errors.add("Plan must contain exactly one VALIDATE step");
        }
        if (synthesisIndexes.size() != 1) {
            errors.add("Plan must contain exactly one SYNTHESIZE step");
        }
        if (validationIndexes.size() != 1 || synthesisIndexes.size() != 1) {
            return;
        }

        int validationIndex = validationIndexes.getFirst();
        int synthesisIndex = synthesisIndexes.getFirst();
        AgentStep validation = steps.get(validationIndex);
        AgentStep synthesis = steps.get(synthesisIndex);
        if (synthesisIndex != steps.size() - 1) {
            errors.add("SYNTHESIZE must be the last plan step");
        }
        if (!synthesis.dependsOn().contains(validation.id())) {
            errors.add("SYNTHESIZE must directly depend on VALIDATE");
        }

        List<AgentStep> businessSteps = steps.stream()
                .filter(step -> step != null && isBusinessAction(step.action()))
                .toList();
        if (businessSteps.isEmpty()) {
            errors.add("At least one business step must execute before VALIDATE");
        }
        for (AgentStep businessStep : businessSteps) {
            int businessIndex = steps.indexOf(businessStep);
            if (businessIndex > validationIndex) {
                errors.add("Business step " + businessStep.id() + " must not follow VALIDATE");
            }
            if (!dependsTransitivelyOn(validation, businessStep.id(), stepsById, new HashSet<>())) {
                errors.add("VALIDATE must close business branch ending at step " + businessStep.id());
            }
        }
    }

    private List<Integer> actionIndexes(List<AgentStep> steps, AgentAction action) {
        List<Integer> indexes = new ArrayList<>();
        for (int index = 0; index < steps.size(); index++) {
            AgentStep step = steps.get(index);
            if (step != null && step.action() == action) {
                indexes.add(index);
            }
        }
        return indexes;
    }

    private boolean dependsTransitivelyOn(
            AgentStep step,
            String expectedDependencyId,
            Map<String, AgentStep> stepsById,
            Set<String> visited
    ) {
        if (step.id() != null && !visited.add(step.id())) {
            return false;
        }
        for (String dependencyId : step.dependsOn()) {
            if (expectedDependencyId.equals(dependencyId)) {
                return true;
            }
            AgentStep dependency = stepsById.get(dependencyId);
            if (dependency == null) {
                continue;
            }
            if (dependsTransitivelyOn(dependency, expectedDependencyId, stepsById, visited)) {
                return true;
            }
        }
        return false;
    }

    private boolean isBusinessAction(AgentAction action) {
        return action != null && action != AgentAction.VALIDATE && action != AgentAction.SYNTHESIZE;
    }

    private AgentPlanValidationResult invalid(List<String> errors) {
        return new AgentPlanValidationResult(false, errors);
    }
}
