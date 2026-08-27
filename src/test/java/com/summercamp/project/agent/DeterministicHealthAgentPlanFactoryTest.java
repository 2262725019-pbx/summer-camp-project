package com.summercamp.project.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class DeterministicHealthAgentPlanFactoryTest {
    private static final String GOAL = """
            请制定未来7天大学生健康生活规划。
            所在地：镇江市
            每周训练：4次
            每次训练：60分钟
            兼顾天气、运动、饮食和作息。
            """;
    private final DeterministicHealthAgentPlanFactory factory =
            new DeterministicHealthAgentPlanFactory();

    @Test
    void createsDynamicValidatedHealthPlanWithoutUnrequestedSideEffects() {
        AgentPlan plan = factory.create(GOAL).orElseThrow();

        assertTrue(new AgentPlanValidator().validate(plan).valid());
        assertTrue(new GoalCoverageValidator().validate(GOAL, plan).valid());
        assertEquals(List.of(
                AgentAction.GET_DATETIME,
                AgentAction.GET_WEATHER,
                AgentAction.RUN_EXERCISE_SKILL,
                AgentAction.RUN_MEAL_SKILL,
                AgentAction.VALIDATE,
                AgentAction.SYNTHESIZE),
                plan.steps().stream().map(AgentStep::action).toList());
        assertEquals("镇江市", plan.steps().get(1).inputs().get("location"));
        assertFalse(plan.steps().stream().anyMatch(step -> step.action() == AgentAction.CREATE_TODO));
    }

    @Test
    void refusesUnsupportedOrWeatherGoalWithoutExplicitLocation() {
        assertFalse(factory.create("给我讲一个笑话").isPresent());
        assertFalse(factory.create("未来7天兼顾天气、运动、饮食和作息").isPresent());
    }
}
