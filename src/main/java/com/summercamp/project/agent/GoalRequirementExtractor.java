package com.summercamp.project.agent;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public final class GoalRequirementExtractor {
    private static final List<String> WEATHER_TERMS = List.of(
            "天气", "气温", "下雨", "户外天气"
    );
    private static final List<String> EXERCISE_TERMS = List.of(
            "运动", "训练", "健身", "锻炼", "跑步", "自重"
    );
    private static final List<String> MEAL_TERMS = List.of(
            "饮食", "营养", "餐食", "食谱", "增肌饮食"
    );
    private static final List<String> LIFESTYLE_TERMS = List.of(
            "作息", "睡眠", "早睡", "生活习惯"
    );

    public Set<GoalRequirement> extract(String goal) {
        if (goal == null || goal.isBlank()) {
            return Set.of();
        }
        EnumSet<GoalRequirement> requirements = EnumSet.noneOf(GoalRequirement.class);
        addIfExplicit(goal, WEATHER_TERMS, GoalRequirement.WEATHER, requirements);
        addIfExplicit(goal, EXERCISE_TERMS, GoalRequirement.EXERCISE, requirements);
        addIfExplicit(goal, MEAL_TERMS, GoalRequirement.MEAL, requirements);
        addIfExplicit(goal, LIFESTYLE_TERMS, GoalRequirement.LIFESTYLE, requirements);
        return Set.copyOf(requirements);
    }

    private void addIfExplicit(
            String goal,
            List<String> terms,
            GoalRequirement requirement,
            Set<GoalRequirement> requirements
    ) {
        if (terms.stream().anyMatch(goal::contains)) {
            requirements.add(requirement);
        }
    }
}
