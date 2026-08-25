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
