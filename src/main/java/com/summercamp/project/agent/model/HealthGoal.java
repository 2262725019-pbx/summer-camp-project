package com.summercamp.project.agent.model;

import java.util.List;

public record HealthGoal(
        HealthGoalType goalType,
        Integer days,
        String gender,
        Integer age,
        Double heightCm,
        Double weightKg,
        String location,
        Integer trainingDaysPerWeek,
        Integer minutesPerSession,
        Integer mealsPerDay,
        String activityLevel,
        Boolean healthConfirmed,
        Boolean noFoodAllergies,
        List<String> safetyFlags,
        String sourceText) {

    public HealthGoal {
        gender = normalize(gender);
        location = normalize(location);
        activityLevel = normalize(activityLevel);
        safetyFlags = safetyFlags == null ? List.of() : List.copyOf(safetyFlags);
        sourceText = normalize(sourceText);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }
}
