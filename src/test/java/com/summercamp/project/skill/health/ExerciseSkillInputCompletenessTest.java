package com.summercamp.project.skill.health;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ExerciseSkillInputCompletenessTest {

    private final ExerciseSkillInputCompleteness checker =
            new ExerciseSkillInputCompleteness();

    @Test
    void requiresEveryCoreFieldFromTheExerciseSkillContract() {
        List<String> fields = List.of(
                "所在地：镇江市",
                "运动目标：增肌",
                "每周训练：4次",
                "每次训练：60分钟",
                "健康确认：健康成人、无食物过敏");

        assertTrue(checker.isComplete(String.join("\n", fields)));
        for (String missing : fields) {
            String request = fields.stream()
                    .filter(field -> !field.equals(missing))
                    .collect(java.util.stream.Collectors.joining("\n"));
            assertFalse(checker.isComplete(request), "missing field must fail: " + missing);
        }
    }

    @Test
    void acceptsExplicitEquivalentFrequencyDurationAndHealthFields() {
        String request = """
                所在城市：镇江
                健身目标：提升心肺
                训练频率：三次
                单次运动：1小时
                身体状况：无明显身体不适
                """;

        assertTrue(checker.isComplete(request));
    }
}
