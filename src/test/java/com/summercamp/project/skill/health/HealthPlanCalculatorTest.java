package com.summercamp.project.skill.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summercamp.project.skill.health.HealthPlanCalculator.MealPlan;
import com.summercamp.project.skill.health.HealthPlanCalculator.Metrics;
import com.summercamp.project.skill.health.HealthProfileParser.Goal;
import com.summercamp.project.skill.health.HealthProfileParser.Profile;
import com.summercamp.project.skill.nutrition.FoodCatalog;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class HealthPlanCalculatorTest {

    private final FoodCatalog foods = new FoodCatalog(new ObjectMapper());

    @Test
    void shouldCalculateMetricsForCuttingProfile() {
        Profile profile = profile(Goal.CUT);
        Metrics metrics = HealthPlanCalculator.calculate(profile, LocalDate.of(2026, 8, 25));

        assertEquals(22.857, metrics.bmi(), 0.01);
        assertEquals(1698.75, metrics.bmr(), 0.01);
        assertEquals(2335.78, metrics.tdee(), 0.01);
        assertTrue(metrics.targetCalories() < metrics.tdee());
        assertEquals(126.0, metrics.proteinG(), 0.01);
        assertTrue(metrics.targetWeightKg() < profile.weightKg());
        assertEquals(LocalDate.of(2026, 9, 24), metrics.targetDate());
        assertEquals(126, metrics.proteinRounded());
    }

    @Test
    void shouldApplySurplusForBulkingAndKeepMaintenanceCalories() {
        Profile bulk = profile(Goal.BULK);
        Profile maintain = profile(Goal.MAINTAIN);

        Metrics bulkMetrics = HealthPlanCalculator.calculate(bulk, LocalDate.of(2026, 8, 25));
        Metrics maintainMetrics = HealthPlanCalculator.calculate(maintain, LocalDate.of(2026, 8, 25));

        assertTrue(bulkMetrics.targetCalories() > bulkMetrics.tdee());
        assertTrue(bulkMetrics.targetWeightKg() > bulk.weightKg());
        assertEquals(bulkMetrics.tdee(), maintainMetrics.targetCalories(), 0.01);
        assertEquals(0, maintainMetrics.weeklyRateKg(), 0.001);
    }

    @Test
    void shouldAdjustProteinPerGoal() {
        assertEquals(70 * 1.8, HealthPlanCalculator.calculate(profile(Goal.CUT), LocalDate.now()).proteinG(), 0.01);
        assertEquals(70 * 1.8, HealthPlanCalculator.calculate(profile(Goal.BULK), LocalDate.now()).proteinG(), 0.01);
        assertEquals(70 * 1.4, HealthPlanCalculator.calculate(profile(Goal.MAINTAIN), LocalDate.now()).proteinG(), 0.01);
    }

    @Test
    void shouldBuildMealPlanCloseToTargetCalories() {
        Metrics metrics = HealthPlanCalculator.calculate(profile(Goal.CUT), LocalDate.of(2026, 8, 25));
        MealPlan mealPlan = HealthPlanCalculator.buildMealPlan(profile(Goal.CUT), metrics, foods);

        assertFalse(mealPlan.meals().isEmpty());
        assertTrue(mealPlan.meals().size() >= 3);
        double relativeError = Math.abs(mealPlan.totals().calories() - metrics.targetCalories())
                / metrics.targetCalories();
        assertTrue(relativeError < 0.15, "餐单热量偏离目标超过 15%：" + mealPlan.totals().calories());
        assertTrue(mealPlan.totals().protein() > 0);
        assertTrue(mealPlan.totals().carbs() > 0);
        assertTrue(mealPlan.totals().fat() > 0);
    }

    @Test
    void shouldAdjustActivityFactorByWeeklyTrainingSessions() {
        Profile sedentary = profile(Goal.CUT);
        Profile moderate = new Profile(Goal.CUT, true, 20, 175.0, 70.0, 30, null, null, null, 4, 3);
        Profile active = new Profile(Goal.CUT, true, 20, 175.0, 70.0, 30, null, null, null, 4, 6);

        Metrics baseline = HealthPlanCalculator.calculate(sedentary, LocalDate.of(2026, 8, 25));
        assertEquals(1698.75 * 1.375, baseline.tdee(), 0.01);
        Metrics medium = HealthPlanCalculator.calculate(moderate, LocalDate.of(2026, 8, 25));
        assertEquals(1698.75 * 1.55, medium.tdee(), 0.01);
        Metrics high = HealthPlanCalculator.calculate(active, LocalDate.of(2026, 8, 25));
        assertEquals(1698.75 * 1.65, high.tdee(), 0.01);
    }

    @Test
    void shouldScaleWeightLossRateByBodyWeight() {
        Profile light = new Profile(Goal.CUT, true, 20, 175.0, 45.0, 30, null, null, null, 4, null);
        Profile heavy = new Profile(Goal.CUT, true, 20, 175.0, 100.0, 30, null, null, null, 4, null);
        Profile veryHeavy = new Profile(Goal.CUT, true, 20, 175.0, 150.0, 30, null, null, null, 4, null);

        assertEquals(-0.315, HealthPlanCalculator.calculate(light, LocalDate.now()).weeklyRateKg(), 0.001);
        assertEquals(-0.7, HealthPlanCalculator.calculate(heavy, LocalDate.now()).weeklyRateKg(), 0.001);
        // 大基数按百分比超过 1kg/周，应被钳制到安全上限
        assertEquals(-1.0, HealthPlanCalculator.calculate(veryHeavy, LocalDate.now()).weeklyRateKg(), 0.001);
    }

    @Test
    void shouldTailorSnackByTrainingPreference() {
        Profile strength = new Profile(Goal.CUT, true, 20, 175.0, 70.0, 30, null, null, "健身", 4, null);
        Profile endurance = new Profile(Goal.CUT, true, 20, 175.0, 70.0, 30, null, null, "跑步", 4, null);

        Metrics metrics = HealthPlanCalculator.calculate(strength, LocalDate.of(2026, 8, 25));
        MealPlan strengthPlan = HealthPlanCalculator.buildMealPlan(strength, metrics, foods);
        MealPlan endurancePlan = HealthPlanCalculator.buildMealPlan(endurance, metrics, foods);

        assertTrue(containsFood(strengthPlan, "花生酱"));
        assertTrue(containsFood(endurancePlan, "香蕉"));
        assertFalse(containsFood(endurancePlan, "花生酱"));
    }

    private boolean containsFood(MealPlan mealPlan, String foodName) {
        return mealPlan.meals().stream()
                .flatMap(meal -> meal.portions().stream())
                .anyMatch(portion -> portion.foodName().equals(foodName));
    }

    private Profile profile(Goal goal) {
        return new Profile(goal, true, 20, 175.0, 70.0, 30, null, null, null, 4, null);
    }
}
