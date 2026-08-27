package com.summercamp.project.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GoalRequirementExtractorTest {
    private final GoalRequirementExtractor extractor = new GoalRequirementExtractor();

    @Test
    void extractsOnlyExplicitlyRequestedHealthDomains() {
        assertEquals(
                Set.of(
                        GoalRequirement.WEATHER,
                        GoalRequirement.EXERCISE,
                        GoalRequirement.MEAL,
                        GoalRequirement.LIFESTYLE,
                        GoalRequirement.TEMPORAL
                ),
                extractor.extract("未来7天兼顾饮食、运动、作息，并根据天气调整户外安排")
        );
        assertEquals(Set.of(GoalRequirement.MEAL), extractor.extract("制定增肌饮食计划"));
        assertEquals(Set.of(GoalRequirement.EXERCISE), extractor.extract("制定自重训练方案"));
        assertEquals(Set.of(), extractor.extract("制定大学生健康生活方案"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "未来7天健康生活规划",
            "未来 7 天健康生活规划",
            "接下来7天",
            "接下来 7 天",
            "未来七天",
            "接下来七天",
            "未来10天",
            "未来3天",
            "接下来5天"
    })
    void detectsExplicitRelativeDayPlanning(String goal) {
        assertTrue(extractor.extract(goal).contains(GoalRequirement.TEMPORAL));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "给我一个增肌饮食建议",
            "过段时间帮我安排运动",
            "最近睡眠不规律",
            "以后注意健康",
            "未来0天健康计划",
            "未来32天健康计划"
    })
    void doesNotInferTemporalRequirementFromNonTemporalOrOutOfRangeGoals(String goal) {
        assertFalse(extractor.extract(goal).contains(GoalRequirement.TEMPORAL));
    }
}
