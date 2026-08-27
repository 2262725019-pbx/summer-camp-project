package com.summercamp.project.agent;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GoalRequirementExtractor {
    private static final Pattern EXPLICIT_PLAN_DAYS = Pattern.compile(
            "(?:未来|接下来)\\s*(\\d{1,2}|一|二|三|四|五|六|七|八|九|十)\\s*天"
    );
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
        if (explicitPlanDays(goal) != null) {
            requirements.add(GoalRequirement.TEMPORAL);
        }
        addIfExplicit(goal, WEATHER_TERMS, GoalRequirement.WEATHER, requirements);
        addIfExplicit(goal, EXERCISE_TERMS, GoalRequirement.EXERCISE, requirements);
        addIfExplicit(goal, MEAL_TERMS, GoalRequirement.MEAL, requirements);
        addIfExplicit(goal, LIFESTYLE_TERMS, GoalRequirement.LIFESTYLE, requirements);
        return Set.copyOf(requirements);
    }

    Integer explicitPlanDays(String goal) {
        if (goal == null || goal.isBlank()) {
            return null;
        }
        Matcher matcher = EXPLICIT_PLAN_DAYS.matcher(goal);
        if (!matcher.find()) {
            return null;
        }
        int days = parseDayCount(matcher.group(1));
        return days >= 1 && days <= 31 ? days : null;
    }

    private int parseDayCount(String value) {
        return switch (value) {
            case "一" -> 1;
            case "二" -> 2;
            case "三" -> 3;
            case "四" -> 4;
            case "五" -> 5;
            case "六" -> 6;
            case "七" -> 7;
            case "八" -> 8;
            case "九" -> 9;
            case "十" -> 10;
            default -> Integer.parseInt(value);
        };
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
