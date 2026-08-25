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

    private Profile profile(Goal goal) {
        return new Profile(goal, true, 20, 175.0, 70.0, 30, null, null, null, 4);
    }
}
