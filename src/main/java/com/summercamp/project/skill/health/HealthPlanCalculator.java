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

    /** 大学生久坐基线的活动系数；随每周训练次数上调。 */
    private static final double SEDENTARY_ACTIVITY_FACTOR = 1.375;

    /** 每减/增 1kg 体重约需 7700 千卡热量差，按周计算日差值。 */
    private static final double KCAL_PER_KG = 7_700;

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
        double tdee = bmr * activityFactor(profile);

        // 每周体重变化按体重的百分比估算（减脂 0.7%、增肌 0.35%），
        // 再反推每日热量差，保证速率与热量目标自洽。
        double weeklyRateKg = switch (profile.goal()) {
            case CUT -> clamp(profile.weightKg() * 0.007, 0.25, 1.0);
            case BULK -> clamp(profile.weightKg() * 0.0035, 0.15, 0.5);
            case MAINTAIN -> 0;
        };
        double targetCalories = switch (profile.goal()) {
            case CUT -> tdee - clamp(weeklyRateKg * KCAL_PER_KG / 7.0, 250, tdee * 0.25);
            case BULK -> tdee + clamp(weeklyRateKg * KCAL_PER_KG / 7.0, 200, tdee * 0.15);
            case MAINTAIN -> tdee;
        };
        double signedWeeklyRateKg = switch (profile.goal()) {
            case CUT -> -weeklyRateKg;
            case BULK -> weeklyRateKg;
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
                + signedWeeklyRateKg * periodDays / 7.0;
        return new Metrics(
                bmi,
                bmr,
                tdee,
                targetCalories,
                proteinG,
                carbsG,
                fatG,
                signedWeeklyRateKg,
                targetWeightKg,
                today.plusDays(periodDays));
    }

    /** 活动系数：0 次（未提供）按久坐 1.375，1-2 次 1.45，3-4 次 1.55，5 次及以上 1.65。 */
    private static double activityFactor(Profile profile) {
        int sessions = profile.weeklyTraining() == null ? 0 : profile.weeklyTraining();
        if (sessions >= 5) {
            return 1.65;
        }
        if (sessions >= 3) {
            return 1.55;
        }
        if (sessions >= 1) {
            return 1.45;
        }
        return SEDENTARY_ACTIVITY_FACTOR;
    }

    /** 按目标热量线性缩放固定模板并做营养修正，返回近似餐单。 */
    public static MealPlan buildMealPlan(Profile profile, Metrics metrics, FoodCatalog foods) {
        List<PortionTemplate> templates = template(profile);
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

    private static List<PortionTemplate> template(Profile profile) {
        int mealsPerDay = profile.mealsPerDay() == null ? 4 : profile.mealsPerDay();
        // 力量/增肌偏好 → 高蛋白加餐与瘦牛肉；耐力偏好 → 训练前后碳水补给
        boolean strength = profile.goal() == Goal.BULK
                || hasAny(profile.trainingPreference(), "健身", "力量", "帕梅拉", "hiit");
        boolean endurance = !strength && hasAny(
                profile.trainingPreference(),
                "跑步", "慢跑", "游泳", "篮球", "足球", "羽毛球", "跳绳", "骑行", "单车", "瑜伽", "有氧");
        List<PortionTemplate> templates = new ArrayList<>();
        templates.add(new PortionTemplate("早餐", "oats", 70, 30, 180));
        templates.add(new PortionTemplate("早餐", "milk", 250, 100, 600));
        templates.add(new PortionTemplate("早餐", "egg", 100, 50, 250));
        templates.add(new PortionTemplate("午餐", "rice", 250, 80, 650));
        templates.add(new PortionTemplate("午餐", "chicken", 170, 80, 400));
        templates.add(new PortionTemplate("午餐", "broccoli", 200, 100, 400));
        templates.add(new PortionTemplate("午餐", "olive-oil", 10, 0, 35));
        if (mealsPerDay >= 4) {
            if (strength) {
                templates.add(new PortionTemplate("加餐", "yogurt", 200, 100, 400));
                templates.add(new PortionTemplate("加餐", "whole-wheat-bread", 80, 0, 250));
                templates.add(new PortionTemplate("加餐", "peanut-butter", 15, 0, 40));
            } else if (endurance) {
                templates.add(new PortionTemplate("加餐", "banana", 100, 0, 250));
                templates.add(new PortionTemplate("加餐", "sweet-potato", 150, 0, 300));
                templates.add(new PortionTemplate("加餐", "almond", 10, 0, 40));
            } else {
                templates.add(new PortionTemplate("加餐", "yogurt", 200, 100, 500));
                templates.add(new PortionTemplate("加餐", "whole-wheat-bread", 80, 0, 250));
                templates.add(new PortionTemplate("加餐", "almond", 15, 0, 60));
            }
        }
        if (mealsPerDay >= 5) {
            templates.add(new PortionTemplate("晚餐", "rice", 220, 80, 650));
            templates.add(new PortionTemplate("晚餐", strength ? "lean-beef" : "chicken", 150, 80, 350));
            templates.add(new PortionTemplate("晚餐", "spinach", 200, 100, 400));
        } else {
            templates.add(new PortionTemplate("晚餐", "rice", 220, 80, 650));
            templates.add(new PortionTemplate("晚餐", strength ? "lean-beef" : "chicken", 150, 80, 350));
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

    private static boolean hasAny(String preference, String... words) {
        if (preference == null) {
            return false;
        }
        return List.of(words).contains(preference);
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
