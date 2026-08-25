package com.summercamp.project.skill.health;

import com.summercamp.project.skill.health.HealthProfileParser.Goal;
import com.summercamp.project.skill.health.HealthProfileParser.Profile;
import com.summercamp.project.skill.nutrition.FoodCatalog;
import com.summercamp.project.skill.nutrition.FoodItem;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 健康规划所需的本地确定性计算：BMI、基础代谢、每日热量与宏量营养素目标、
 * 达成日期估算，以及基于本地食物库的参考餐单。所有数值不经过模型，保证准确。
 */
public final class HealthPlanCalculator {

    /** 大学生普遍以久坐上课为主，统一按"轻度活动"系数估算。 */
    private static final double ACTIVITY_FACTOR = 1.375;

    private HealthPlanCalculator() {
    }

    /** 本地计算结果。 */
    public record Metrics(
            double bmi,
            double bmr,
            double tdee,
            double targetCalories,
            double proteinG,
            double carbsG,
            double fatG,
            double weeklyRateKg,
            double targetWeightKg,
            LocalDate targetDate) {

        public long caloriesRounded() {
            return Math.round(targetCalories);
        }

        public long proteinRounded() {
            return Math.round(proteinG);
        }

        public long carbsRounded() {
            return Math.round(carbsG);
        }

        public long fatRounded() {
            return Math.round(fatG);
        }
    }

    /** 参考餐单：每餐食物与克数，以及整日营养合计。 */
    public record MealPlan(List<Meal> meals, Totals totals) {
    }

    public record Meal(String name, List<Portion> portions) {
    }

    public record Portion(String foodName, double grams) {
    }

    public record Totals(double calories, double protein, double carbs, double fat) {
    }

    public static Metrics calculate(Profile profile, LocalDate today) {
        double heightM = profile.heightCm() / 100.0;
        double bmi = profile.weightKg() / (heightM * heightM);
        double bmr = 10 * profile.weightKg() + 6.25 * profile.heightCm()
                - 5 * profile.age() + (Boolean.TRUE.equals(profile.male()) ? 5 : -161);
        double tdee = bmr * ACTIVITY_FACTOR;

        double targetCalories = switch (profile.goal()) {
            case CUT -> tdee - clamp(tdee * 0.20, 300, 500);
            case BULK -> tdee + clamp(tdee * 0.10, 200, 400);
            case MAINTAIN -> tdee;
        };
        double weeklyRateKg = switch (profile.goal()) {
            case CUT -> -0.6;
            case BULK -> 0.3;
            case MAINTAIN -> 0;
        };

        double proteinPerKg = switch (profile.goal()) {
            case CUT, BULK -> 1.8;
            case MAINTAIN -> 1.4;
        };
        double proteinG = proteinPerKg * profile.weightKg();
        double fatG = targetCalories * 0.25 / 9;
        double carbsG = Math.max(0, (targetCalories - proteinG * 4 - fatG * 9) / 4);

        int periodDays = Math.max(1, profile.periodDays());
        double targetWeightKg = profile.weightKg()
                + (profile.goal() == Goal.MAINTAIN ? 0 : weeklyRateKg * periodDays / 7.0);
        return new Metrics(
                bmi,
                bmr,
                tdee,
                targetCalories,
                proteinG,
                carbsG,
                fatG,
                weeklyRateKg,
                targetWeightKg,
                today.plusDays(periodDays));
    }

    /** 按目标热量线性缩放固定模板并做营养修正，返回近似餐单。 */
    public static MealPlan buildMealPlan(Profile profile, Metrics metrics, FoodCatalog foods) {
        int mealsPerDay = profile.mealsPerDay() == null ? 4 : profile.mealsPerDay();
        List<PortionTemplate> templates = template(mealsPerDay);
        List<MutablePortion> portions = templates.stream()
                .map(template -> new MutablePortion(
                        template.mealName(),
                        foods.require(template.foodId()),
                        template.grams(),
                        template.minimum(),
                        template.maximum()))
                .toList();

        double scale = metrics.targetCalories() / totals(portions).calories();
        for (MutablePortion portion : portions) {
            portion.grams = roundToFive(clamp(portion.grams * scale, portion.minimum, portion.maximum));
        }

        Map<String, List<MutablePortion>> grouped = new LinkedHashMap<>();
        for (MutablePortion portion : portions) {
            if (portion.grams >= 2.5) {
                grouped.computeIfAbsent(portion.mealName, ignored -> new ArrayList<>()).add(portion);
            }
        }
        List<Meal> meals = new ArrayList<>();
        for (Map.Entry<String, List<MutablePortion>> entry : grouped.entrySet()) {
            meals.add(new Meal(
                    entry.getKey(),
                    entry.getValue().stream()
                            .map(portion -> new Portion(portion.food.name(), portion.grams))
                            .toList()));
        }
        Totals actual = totals(portions);
        return new MealPlan(meals, new Totals(roundOne(actual.calories()), roundOne(actual.protein()),
                roundOne(actual.carbs()), roundOne(actual.fat())));
    }

    private record PortionTemplate(String mealName, String foodId, double grams,
                                   double minimum, double maximum) {
    }

    private static List<PortionTemplate> template(int mealsPerDay) {
        List<PortionTemplate> templates = new ArrayList<>();
        templates.add(new PortionTemplate("早餐", "oats", 70, 30, 180));
        templates.add(new PortionTemplate("早餐", "milk", 250, 100, 600));
        templates.add(new PortionTemplate("早餐", "egg", 100, 50, 250));
        templates.add(new PortionTemplate("午餐", "rice", 250, 80, 650));
        templates.add(new PortionTemplate("午餐", "chicken", 170, 80, 400));
        templates.add(new PortionTemplate("午餐", "broccoli", 200, 100, 400));
        templates.add(new PortionTemplate("午餐", "olive-oil", 10, 0, 35));
        if (mealsPerDay >= 4) {
            templates.add(new PortionTemplate("加餐", "yogurt", 200, 100, 500));
            templates.add(new PortionTemplate("加餐", "whole-wheat-bread", 80, 0, 250));
            templates.add(new PortionTemplate("加餐", "almond", 15, 0, 60));
        }
        if (mealsPerDay >= 5) {
            templates.add(new PortionTemplate("晚餐", "rice", 220, 80, 650));
            templates.add(new PortionTemplate("晚餐", "lean-beef", 150, 80, 350));
            templates.add(new PortionTemplate("晚餐", "spinach", 200, 100, 400));
        } else {
            templates.add(new PortionTemplate("晚餐", "rice", 220, 80, 650));
            templates.add(new PortionTemplate("晚餐", "chicken", 150, 80, 350));
            templates.add(new PortionTemplate("晚餐", "spinach", 200, 100, 400));
            templates.add(new PortionTemplate("晚餐", "olive-oil", 10, 0, 35));
        }
        return templates;
    }

    private static final class MutablePortion {
        private final String mealName;
        private final FoodItem food;
        private final double minimum;
        private final double maximum;
        private double grams;

        private MutablePortion(String mealName, FoodItem food, double grams,
                               double minimum, double maximum) {
            this.mealName = mealName;
            this.food = food;
            this.grams = grams;
            this.minimum = minimum;
            this.maximum = maximum;
        }
    }

    private static Totals totals(List<MutablePortion> portions) {
        double calories = 0;
        double protein = 0;
        double carbs = 0;
        double fat = 0;
        for (MutablePortion portion : portions) {
            double factor = portion.grams / 100;
            calories += portion.food.calories() * factor;
            protein += portion.food.protein() * factor;
            carbs += portion.food.carbohydrates() * factor;
            fat += portion.food.fat() * factor;
        }
        return new Totals(calories, protein, carbs, fat);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static long roundToFive(double value) {
        return Math.round(value / 5) * 5;
    }

    private static double roundOne(double value) {
        return Math.round(value * 10) / 10.0;
    }

    /** 以固定中文数字格式输出，避免在测试与日志中出现区域差异。 */
    public static String oneDecimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
