package com.summercamp.project.agent.planning;

import static org.assertj.core.api.Assertions.assertThat;

import com.summercamp.project.agent.model.HealthGoal;
import com.summercamp.project.agent.model.HealthGoalType;
import org.junit.jupiter.api.Test;

class HealthGoalParserTest {

    private final HealthGoalParser parser = new HealthGoalParser();

    @Test
    void parsesACompleteNaturalLanguageGoal() {
        HealthGoal goal = parser.parse("我想要一份未来 7 天的增肌健康生活方案。我 20 岁，男，"
                + "身高 175cm，体重 70kg，住在上海，每周训练 4 次，每次训练 60 分钟，"
                + "每天吃 4 餐，身体健康，没有食物过敏。");

        assertThat(goal.goalType()).isEqualTo(HealthGoalType.MUSCLE_GAIN);
        assertThat(goal.days()).isEqualTo(7);
        assertThat(goal.gender()).isEqualTo("男");
        assertThat(goal.age()).isEqualTo(20);
        assertThat(goal.heightCm()).isEqualTo(175);
        assertThat(goal.weightKg()).isEqualTo(70);
        assertThat(goal.location()).isEqualTo("上海");
        assertThat(goal.trainingDaysPerWeek()).isEqualTo(4);
        assertThat(goal.minutesPerSession()).isEqualTo(60);
        assertThat(goal.mealsPerDay()).isEqualTo(4);
        assertThat(goal.activityLevel()).isEqualTo("中度");
        assertThat(goal.healthConfirmed()).isTrue();
        assertThat(goal.noFoodAllergies()).isTrue();
        assertThat(goal.safetyFlags()).isEmpty();
    }

    @Test
    void normalizesHighIntensityActivitySynonyms() {
        HealthGoal goal = parser.parse("日常活动：重度");

        assertThat(goal.activityLevel()).isEqualTo("高度");
    }

    @Test
    void parsesCommonConversationalPhrases() {
        HealthGoal goal = parser.parse("我想减脂，性别男，年龄20，身高1米75，体重70kg，"
                + "我在上海上学，一周练四次，每次一小时，一天三顿，身体健康，无食物过敏，做一周计划");

        assertThat(goal.goalType()).isEqualTo(HealthGoalType.FAT_LOSS);
        assertThat(goal.days()).isEqualTo(7);
        assertThat(goal.age()).isEqualTo(20);
        assertThat(goal.heightCm()).isEqualTo(175);
        assertThat(goal.location()).isEqualTo("上海");
        assertThat(goal.trainingDaysPerWeek()).isEqualTo(4);
        assertThat(goal.minutesPerSession()).isEqualTo(60);
        assertThat(goal.mealsPerDay()).isEqualTo(3);
    }

    @Test
    void recognizesAllSupportedHealthGoals() {
        assertThat(parser.parse("我要增肌").goalType()).isEqualTo(HealthGoalType.MUSCLE_GAIN);
        assertThat(parser.parse("我要减肥").goalType()).isEqualTo(HealthGoalType.FAT_LOSS);
        assertThat(parser.parse("我想提高身体素质").goalType()).isEqualTo(HealthGoalType.FITNESS);
        assertThat(parser.parse("我想早睡早起").goalType()).isEqualTo(HealthGoalType.HEALTHY_ROUTINE);
    }

    @Test
    void parsesChinesePlanDaysAndKeepsInvalidValuesForValidation() {
        HealthGoal goal = parser.parse("做未来十四日健康生活方案，每周训练九次，一天两顿");

        assertThat(goal.days()).isEqualTo(14);
        assertThat(goal.trainingDaysPerWeek()).isEqualTo(9);
        assertThat(goal.mealsPerDay()).isEqualTo(2);
        HealthGoalValidator.ValidationResult validation = new HealthGoalValidator().validate(goal);
        assertThat(validation.errors()).contains(
                "每周训练次数应为 1～7 次", "每日餐数应为 3～5 餐");
    }

    @Test
    void identifiesSafetyFlags() {
        HealthGoal goal = parser.parse("我有高血压并且膝盖疼");

        assertThat(goal.healthConfirmed()).isFalse();
        assertThat(goal.safetyFlags()).containsExactly("膝盖疼", "高血压");
    }

    @Test
    void doesNotTreatExplicitlyDeniedConditionsAsSafetyFlags() {
        HealthGoal goal = parser.parse("身体健康，没有高血压，没有糖尿病，没有受伤");

        assertThat(goal.healthConfirmed()).isTrue();
        assertThat(goal.safetyFlags()).isEmpty();
    }
}
