package com.summercamp.project.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class GoalCoverageValidatorTest {
    private final GoalCoverageValidator validator = new GoalCoverageValidator();

    @Test
    void requiresEveryExplicitCapabilityAction() {
        AgentPlan plan = plan(
                AgentAction.GET_DATETIME,
                AgentAction.GET_WEATHER,
                AgentAction.RUN_MEAL_SKILL,
                AgentAction.VALIDATE,
                AgentAction.SYNTHESIZE
        );

        AgentPlanValidationResult result = validator.validate(
                "未来7天兼顾饮食、运动、作息和天气", plan);

        assertEquals(
                List.of(GoalCoverageValidator.MISSING_REQUIRED_EXERCISE_ACTION),
                result.errors()
        );
    }

    @Test
    void doesNotInferMealOrExerciseWhenOnlyTheOtherDomainWasRequested() {
        assertTrue(validator.validate(
                "只制定饮食计划",
                plan(AgentAction.RUN_MEAL_SKILL, AgentAction.VALIDATE, AgentAction.SYNTHESIZE)
        ).valid());
        assertTrue(validator.validate(
                "只制定运动计划",
                plan(AgentAction.RUN_EXERCISE_SKILL, AgentAction.VALIDATE, AgentAction.SYNTHESIZE)
        ).valid());
    }

    @Test
    void requiresDatetimeOnlyForExplicitRelativeDayPlanning() {
        AgentPlan withoutDatetime = plan(
                AgentAction.RETRIEVE_KNOWLEDGE,
                AgentAction.RUN_EXERCISE_SKILL,
                AgentAction.CREATE_TODO,
                AgentAction.VALIDATE,
                AgentAction.SYNTHESIZE
        );

        AgentPlanValidationResult temporal = validator.validate(
                "未来7天健康生活规划", withoutDatetime);

        assertEquals(
                List.of(GoalCoverageValidator.MISSING_REQUIRED_DATETIME_ACTION),
                temporal.errors()
        );
        assertTrue(validator.validate("给我一个增肌饮食建议", plan(
                AgentAction.RUN_MEAL_SKILL,
                AgentAction.RETRIEVE_KNOWLEDGE,
                AgentAction.CALCULATE,
                AgentAction.VALIDATE,
                AgentAction.SYNTHESIZE
        )).valid());
    }

    private AgentPlan plan(AgentAction... actions) {
        List<AgentStep> steps = java.util.stream.IntStream.range(0, actions.length)
                .mapToObj(index -> new AgentStep(
                        "S" + (index + 1),
                        actions[index],
                        "执行",
                        "覆盖目标",
                        List.of()
                ))
                .toList();
        return new AgentPlan("测试目标", steps);
    }
}
