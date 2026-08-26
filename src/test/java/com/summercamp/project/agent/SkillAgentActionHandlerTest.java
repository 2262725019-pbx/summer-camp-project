package com.summercamp.project.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.summercamp.project.llm.ChatMessage;
import com.summercamp.project.skill.BotSkill;
import com.summercamp.project.skill.SkillContext;
import com.summercamp.project.skill.SkillExecutionMode;
import com.summercamp.project.skill.SkillRegistry;
import com.summercamp.project.skill.SkillResult;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SkillAgentActionHandlerTest {
    private final SkillRegistry skillRegistry = mock(SkillRegistry.class);
    private final BotSkill skill = mock(BotSkill.class);

    @Test
    void exerciseUsesNamedSkillAndFallsBackToOriginalGoal() {
        when(skillRegistry.findByName("exercise-health-advice")).thenReturn(Optional.of(skill));
        when(skill.execute(any())).thenReturn(SkillResult.completed("安全运动建议"));
        AgentStep step = step(AgentAction.RUN_EXERCISE_SKILL, Map.of());
        List<ChatMessage> history = List.of(ChatMessage.user("历史消息"));
        AgentExecutionContext context = context(
                "student-9",
                "为健康成年人安排今天的运动",
                history,
                true,
                step
        );

        AgentObservation observation = new ExerciseSkillAgentActionHandler(skillRegistry)
                .execute(step, context);

        assertTrue(observation.success());
        verify(skillRegistry).findByName("exercise-health-advice");
        ArgumentCaptor<SkillContext> contextCaptor = ArgumentCaptor.forClass(SkillContext.class);
        verify(skill).execute(contextCaptor.capture());
        assertEquals("student-9", contextCaptor.getValue().userId());
        assertEquals("为健康成年人安排今天的运动", contextCaptor.getValue().text());
        assertEquals(history, contextCaptor.getValue().history());
        assertTrue(contextCaptor.getValue().voiceMessage());
        assertEquals(SkillExecutionMode.AGENT, contextCaptor.getValue().executionMode());
    }

    @Test
    void mealPreservesOriginalGoalAndUsesExplicitRequestAsSupplement() {
        when(skillRegistry.findByName("muscle-gain-meal-plan")).thenReturn(Optional.of(skill));
        when(skill.execute(any())).thenReturn(SkillResult.completed("增肌饮食计划"));
        AgentStep step = step(
                AgentAction.RUN_MEAL_SKILL,
                Map.of("request", "男，20岁，70kg，制定增肌饮食")
        );

        AgentObservation observation = new MealSkillAgentActionHandler(skillRegistry)
                .execute(step, context("user", "original goal", List.of(), false, step));

        assertTrue(observation.success());
        assertEquals("COMPLETED", observation.structuredData().get("status"));
        verify(skillRegistry).findByName("muscle-gain-meal-plan");
        ArgumentCaptor<SkillContext> contextCaptor = ArgumentCaptor.forClass(SkillContext.class);
        verify(skill).execute(contextCaptor.capture());
        assertEquals(
                "original goal\n\n当前 Agent 步骤补充：男，20岁，70kg，制定增肌饮食",
                contextCaptor.getValue().text()
        );
    }

    @Test
    void exercisePreservesOriginalConstraintsWhenPlannerRequestIsShort() {
        when(skillRegistry.findByName("exercise-health-advice")).thenReturn(Optional.of(skill));
        when(skill.execute(any())).thenReturn(SkillResult.completed("运动安排"));
        String originalGoal = "21岁健康成人，每周训练4次，每次40分钟，喜欢快走和自重训练";
        AgentStep step = step(
                AgentAction.RUN_EXERCISE_SKILL,
                Map.of("request", "生成七天运动方案")
        );

        new ExerciseSkillAgentActionHandler(skillRegistry).execute(
                step, context("user", originalGoal, List.of(), false, step));

        ArgumentCaptor<SkillContext> contextCaptor = ArgumentCaptor.forClass(SkillContext.class);
        verify(skill).execute(contextCaptor.capture());
        assertTrue(contextCaptor.getValue().text().startsWith(originalGoal));
        assertTrue(contextCaptor.getValue().text().contains("当前 Agent 步骤补充：生成七天运动方案"));
        assertTrue(contextCaptor.getValue().text().length()
                <= AbstractSkillAgentActionHandler.MAX_SKILL_REQUEST_CHARS);
    }

    @Test
    void waitingInputIsRecoverableFailureWithoutInventingCompletion() {
        when(skillRegistry.findByName("muscle-gain-meal-plan")).thenReturn(Optional.of(skill));
        when(skill.execute(any())).thenReturn(SkillResult.waitingInput("请补充身高、体重和训练频率"));
        AgentStep step = step(AgentAction.RUN_MEAL_SKILL, Map.of());

        AgentObservation observation = new MealSkillAgentActionHandler(skillRegistry)
                .execute(step, context("user", "增肌饮食", List.of(), false, step));

        assertFalse(observation.success());
        assertEquals("NEEDS_USER_INPUT", observation.structuredData().get("code"));
        assertEquals("true", observation.structuredData().get("recoverable"));
        assertEquals("WAITING_INPUT", observation.structuredData().get("status"));
        assertEquals("请补充身高、体重和训练频率", observation.summary());
    }

    private AgentStep step(AgentAction action, Map<String, String> inputs) {
        return new AgentStep("S1", action, "execute", "test", List.of(), inputs);
    }

    private AgentExecutionContext context(
            String userId,
            String goal,
            List<ChatMessage> history,
            boolean voiceMessage,
            AgentStep step
    ) {
        AgentPlan plan = new AgentPlan(goal, List.of(step));
        return new AgentExecutionContext(
                userId,
                goal,
                history,
                voiceMessage,
                new AgentState(plan),
                plan
        );
    }
}
