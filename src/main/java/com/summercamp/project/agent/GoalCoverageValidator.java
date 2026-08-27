package com.summercamp.project.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class GoalCoverageValidator {
    public static final String MISSING_REQUIRED_EXERCISE_ACTION =
            "MISSING_REQUIRED_EXERCISE_ACTION";
    public static final String MISSING_REQUIRED_MEAL_ACTION =
            "MISSING_REQUIRED_MEAL_ACTION";
    public static final String MISSING_REQUIRED_WEATHER_ACTION =
            "MISSING_REQUIRED_WEATHER_ACTION";
    public static final String MISSING_REQUIRED_DATETIME_ACTION =
            "MISSING_REQUIRED_DATETIME_ACTION";

    private final GoalRequirementExtractor requirementExtractor;

    public GoalCoverageValidator() {
        this(new GoalRequirementExtractor());
    }

    GoalCoverageValidator(GoalRequirementExtractor requirementExtractor) {
        this.requirementExtractor = Objects.requireNonNull(
                requirementExtractor, "requirementExtractor must not be null");
    }

    public AgentPlanValidationResult validate(String originalGoal, AgentPlan plan) {
        if (plan == null) {
            return new AgentPlanValidationResult(false, List.of("Plan must not be null"));
        }
        Set<AgentAction> actions = plan.steps().stream()
                .filter(Objects::nonNull)
                .map(AgentStep::action)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<String> errors = new ArrayList<>();
        for (GoalRequirement requirement : requirementExtractor.extract(originalGoal)) {
            AgentAction requiredAction = requirement.requiredAction();
            if (requiredAction != null && !actions.contains(requiredAction)) {
                errors.add(missingActionCode(requirement));
            }
        }
        return new AgentPlanValidationResult(errors.isEmpty(), errors);
    }

    private String missingActionCode(GoalRequirement requirement) {
        return switch (requirement) {
            case TEMPORAL -> MISSING_REQUIRED_DATETIME_ACTION;
            case EXERCISE -> MISSING_REQUIRED_EXERCISE_ACTION;
            case MEAL -> MISSING_REQUIRED_MEAL_ACTION;
            case WEATHER -> MISSING_REQUIRED_WEATHER_ACTION;
            case LIFESTYLE -> throw new IllegalArgumentException(
                    "LIFESTYLE does not require a dedicated action");
        };
    }
}
