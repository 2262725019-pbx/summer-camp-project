package com.summercamp.project.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import org.junit.jupiter.api.Test;

class GoalRequirementExtractorTest {
    private final GoalRequirementExtractor extractor = new GoalRequirementExtractor();

    @Test
    void extractsOnlyExplicitlyRequestedHealthDomains() {
        assertEquals(
                Set.of(
                        GoalRequirement.WEATHER,
                        GoalRequirement.EXERCISE,
                        GoalRequirement.MEAL,
                        GoalRequirement.LIFESTYLE
                ),
                extractor.extract("未来7天兼顾饮食、运动、作息，并根据天气调整户外安排")
        );
        assertEquals(Set.of(GoalRequirement.MEAL), extractor.extract("制定增肌饮食计划"));
        assertEquals(Set.of(GoalRequirement.EXERCISE), extractor.extract("制定自重训练方案"));
        assertEquals(Set.of(), extractor.extract("制定大学生健康生活方案"));
    }
}
