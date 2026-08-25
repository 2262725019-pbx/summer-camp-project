package com.summercamp.project.agent.planning;

import com.summercamp.project.agent.model.HealthGoal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class HealthGoalValidator {

    public ValidationResult validate(HealthGoal goal) {
        List<String> missing = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        addMissing(missing, goal.goalType() == null, "健康目标（增肌/减脂/提升体能/规律作息）");
        addMissing(missing, goal.days() == null, "计划天数");
        addMissing(missing, goal.gender().isBlank(), "性别");
        addMissing(missing, goal.age() == null, "年龄");
        addMissing(missing, goal.heightCm() == null, "身高");
        addMissing(missing, goal.weightKg() == null, "体重");
        addMissing(missing, goal.location().isBlank(), "所在城市");
        addMissing(missing, goal.trainingDaysPerWeek() == null, "每周训练次数");
        addMissing(missing, goal.minutesPerSession() == null, "每次训练时长");
        addMissing(missing, goal.mealsPerDay() == null, "每日餐数");
        addMissing(missing, goal.healthConfirmed() == null, "健康确认");
        addMissing(missing, goal.noFoodAllergies() == null, "食物过敏确认");

        range(errors, goal.days(), 3, 14, "计划天数应为 3～14 天");
        range(errors, goal.age(), 18, 70, "年龄应为 18～70 岁");
        range(errors, goal.heightCm(), 120, 230, "身高应为 120～230 cm");
        range(errors, goal.weightKg(), 30, 250, "体重应为 30～250 kg");
        range(errors, goal.trainingDaysPerWeek(), 1, 7, "每周训练次数应为 1～7 次");
        range(errors, goal.minutesPerSession(), 20, 180, "每次训练时长应为 20～180 分钟");
        range(errors, goal.mealsPerDay(), 3, 5, "每日餐数应为 3～5 餐");

        if (Boolean.FALSE.equals(goal.healthConfirmed()) || !goal.safetyFlags().isEmpty()) {
            errors.add("当前版本不为存在伤病、疾病、孕期、进食障碍或用药影响的用户自动制定训练计划");
        }
        if (Boolean.FALSE.equals(goal.noFoodAllergies())) {
            errors.add("当前版本暂不为存在食物过敏的用户自动生成餐单");
        }
        return new ValidationResult(missing, errors);
    }

    private void addMissing(List<String> missing, boolean condition, String name) {
        if (condition) {
            missing.add(name);
        }
    }

    private void range(List<String> errors, Number value, double minimum, double maximum, String message) {
        if (value != null && (value.doubleValue() < minimum || value.doubleValue() > maximum)) {
            errors.add(message);
        }
    }

    public record ValidationResult(List<String> missingFields, List<String> errors) {

        public ValidationResult {
            missingFields = List.copyOf(missingFields);
            errors = List.copyOf(errors);
        }

        public boolean valid() {
            return missingFields.isEmpty() && errors.isEmpty();
        }

        public boolean blocked() {
            return !errors.isEmpty();
        }
    }
}
