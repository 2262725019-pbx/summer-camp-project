package com.summercamp.project.agent.planning;

import static org.assertj.core.api.Assertions.assertThat;

import com.summercamp.project.agent.model.HealthGoal;
import com.summercamp.project.agent.model.HealthGoalType;
import java.util.List;
import org.junit.jupiter.api.Test;

class HealthGoalValidatorTest {

    private final HealthGoalValidator validator = new HealthGoalValidator();

    @Test
    void reportsEveryMissingFieldAtOnce() {
        HealthGoal goal = new HealthGoal(
                HealthGoalType.MUSCLE_GAIN, 7, "男", 20, 175.0, 70.0, "上海",
                null, null, null, "", null, null, List.of(), "增肌计划");

        HealthGoalValidator.ValidationResult result = validator.validate(goal);

        assertThat(result.valid()).isFalse();
        assertThat(result.blocked()).isFalse();
        assertThat(result.missingFields()).containsExactly(
                "每周训练次数", "每次训练时长", "每日餐数", "健康确认", "食物过敏确认");
    }

    @Test
    void blocksUnsafeMedicalScenarios() {
        HealthGoal goal = new HealthGoal(
                HealthGoalType.FAT_LOSS, 7, "女", 20, 165.0, 60.0, "北京",
                3, 60, 3, "中度", false, true, List.of("膝盖疼"), "减脂计划");

        HealthGoalValidator.ValidationResult result = validator.validate(goal);

        assertThat(result.blocked()).isTrue();
        assertThat(result.errors()).anyMatch(message -> message.contains("不为存在伤病"));
    }

    @Test
    void acceptsACompleteHealthyAdultGoal() {
        HealthGoal goal = new HealthGoal(
                HealthGoalType.HEALTHY_ROUTINE, 7, "男", 22, 175.0, 70.0, "宜春市",
                4, 60, 4, "中度", true, true, List.of(), "健康生活计划");

        assertThat(validator.validate(goal).valid()).isTrue();
    }
}
