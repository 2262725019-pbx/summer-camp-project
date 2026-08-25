package com.summercamp.project.agent;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class AgentActionInputValidator {
    private static final List<String> WEATHER_PERIOD_VALUES = List.of(
            "CURRENT",
            "TODAY",
            "TOMORROW",
            "DAY_AFTER_TOMORROW",
            "THREE_DAYS"
    );
    private static final Set<String> WEATHER_PERIODS = Set.copyOf(WEATHER_PERIOD_VALUES);
    private static final Map<AgentAction, Set<String>> ALLOWED_INPUTS = allowedInputs();

    public List<String> validate(AgentStep step) {
        List<String> errors = new ArrayList<>();
        if (step == null || step.action() == null) {
            return List.of();
        }

        String label = step.id() == null || step.id().isBlank() ? "<unknown>" : step.id();
        Map<String, String> inputs = step.inputs();
        Set<String> allowed = ALLOWED_INPUTS.get(step.action());
        inputs.keySet().stream()
                .filter(key -> !allowed.contains(key))
                .sorted()
                .forEach(key -> errors.add("Step " + label + " has unsupported input for "
                        + step.action() + ": " + key));

        switch (step.action()) {
            case GET_WEATHER -> {
                requireNonBlank(inputs, "location", label, errors);
                requireNonBlank(inputs, "period", label, errors);
                String period = inputs.get("period");
                if (period != null && !period.isBlank() && !WEATHER_PERIODS.contains(period)) {
                    errors.add("Step " + label + " input period must be one of: "
                            + String.join(", ", WEATHER_PERIOD_VALUES));
                }
            }
            case RETRIEVE_KNOWLEDGE -> requireNonBlank(inputs, "query", label, errors);
            case CALCULATE -> requireNonBlank(inputs, "expression", label, errors);
            case CREATE_TODO -> requireNonBlank(inputs, "item", label, errors);
            case GET_DATETIME, RUN_EXERCISE_SKILL, RUN_MEAL_SKILL, VALIDATE, SYNTHESIZE -> {
                // Optional or empty-only contracts are enforced by the allow-list above.
            }
        }
        return List.copyOf(errors);
    }

    private void requireNonBlank(
            Map<String, String> inputs,
            String name,
            String stepLabel,
            List<String> errors
    ) {
        String value = inputs.get(name);
        if (value == null || value.isBlank()) {
            errors.add("Step " + stepLabel + " requires non-blank input: " + name);
        }
    }

    private static Map<AgentAction, Set<String>> allowedInputs() {
        Map<AgentAction, Set<String>> inputs = new EnumMap<>(AgentAction.class);
        inputs.put(AgentAction.GET_DATETIME, Set.of("timezone"));
        inputs.put(AgentAction.GET_WEATHER, Set.of("location", "period"));
        inputs.put(AgentAction.RETRIEVE_KNOWLEDGE, Set.of("query"));
        inputs.put(AgentAction.RUN_EXERCISE_SKILL, Set.of("request"));
        inputs.put(AgentAction.RUN_MEAL_SKILL, Set.of("request"));
        inputs.put(AgentAction.CALCULATE, Set.of("expression"));
        inputs.put(AgentAction.CREATE_TODO, Set.of("item"));
        inputs.put(AgentAction.VALIDATE, Set.of());
        inputs.put(AgentAction.SYNTHESIZE, Set.of());
        return Map.copyOf(inputs);
    }
}
