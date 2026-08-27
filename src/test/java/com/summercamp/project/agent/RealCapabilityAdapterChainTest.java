package com.summercamp.project.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summercamp.project.llm.ChatMessage;
import com.summercamp.project.rag.RagContext;
import com.summercamp.project.rag.RagDocument;
import com.summercamp.project.rag.RagRetriever;
import com.summercamp.project.skill.BotSkill;
import com.summercamp.project.skill.SkillRegistry;
import com.summercamp.project.skill.SkillResult;
import com.summercamp.project.skill.health.ExerciseHealthAdviceSkill;
import com.summercamp.project.tool.ToolRegistry;
import com.summercamp.project.tool.ToolResult;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class RealCapabilityAdapterChainTest {

    private static final String COMPLETE_HEALTH_GOAL = """
            请帮我制定未来7天的大学生健康生活规划，兼顾天气、运动、饮食和作息。
            所在地：镇江市
            性别：男
            年龄：22
            身高：175cm
            体重：70kg
            日常活动：轻度
            每周训练：4次
            每次训练：60分钟
            每日餐数：4餐
            健康确认：健康成人、无食物过敏
            运动目标：增肌
            喜欢快走和自重训练
            """;

    @Test
    void realAdaptersRunThroughExistingExecutorInDependencyOrder() {
        ObjectMapper objectMapper = new ObjectMapper();
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.invoke(anyString(), anyString(), any())).thenAnswer(invocation ->
                new ToolRegistry.Invocation(
                        true,
                        ToolResult.text(invocation.getArgument(0) + " result"),
                        "{\"success\":true,\"result\":\"" + invocation.getArgument(0) + " result\"}"
                ));

        RagRetriever ragRetriever = mock(RagRetriever.class);
        RagDocument knowledge = new RagDocument(
                "health-1",
                "健康生活",
                List.of("作息", "运动"),
                "健康成年人应循序渐进运动并保持规律作息"
        );
        when(ragRetriever.retrieve("大学生健康生活"))
                .thenReturn(new RagContext(
                        List.of(new RagContext.Hit(knowledge, 10)),
                        "[health-1] 健康成年人应循序渐进运动并保持规律作息"
                ));

        SkillRegistry skillRegistry = mock(SkillRegistry.class);
        BotSkill exerciseSkill = mock(BotSkill.class);
        BotSkill mealSkill = mock(BotSkill.class);
        when(exerciseSkill.execute(any())).thenReturn(SkillResult.completed("运动安排完成"));
        when(mealSkill.execute(any())).thenReturn(SkillResult.completed("饮食安排完成"));
        when(skillRegistry.findByName("exercise-health-advice"))
                .thenReturn(Optional.of(exerciseSkill));
        when(skillRegistry.findByName("muscle-gain-meal-plan"))
                .thenReturn(Optional.of(mealSkill));

        List<String> terminalOrder = new ArrayList<>();
        FakeAgentActionHandler validate = FakeAgentActionHandler.succeeding(
                AgentAction.VALIDATE,
                terminalOrder
        );
        AtomicBoolean synthesisSawEveryObservation = new AtomicBoolean();
        FakeAgentActionHandler synthesis = new FakeAgentActionHandler(
                AgentAction.SYNTHESIZE,
                (step, context) -> {
                    terminalOrder.add(step.id());
                    synthesisSawEveryObservation.set(List.of("S1", "S2", "S3", "S4", "S5", "S6")
                            .stream()
                            .allMatch(previous -> context.state().findObservation(previous).isPresent()));
                    return new AgentObservation(step.id(), true, "finalized by fake");
                }
        );

        AgentPlan plan = new AgentPlan("制定大学生健康生活规划", List.of(
                step("S1", AgentAction.GET_DATETIME, Map.of("timezone", "Asia/Shanghai")),
                step("S2", AgentAction.GET_WEATHER,
                        Map.of("location", "镇江", "period", "THREE_DAYS"), "S1"),
                step("S3", AgentAction.RETRIEVE_KNOWLEDGE,
                        Map.of("query", "大学生健康生活"), "S2"),
                step("S4", AgentAction.RUN_EXERCISE_SKILL,
                        Map.of("request", "为健康大学生安排运动"), "S3"),
                step("S5", AgentAction.RUN_MEAL_SKILL,
                        Map.of("request", "男，20岁，70kg，制定一般性增肌饮食"), "S4"),
                step("S6", AgentAction.VALIDATE, Map.of(), "S5"),
                step("S7", AgentAction.SYNTHESIZE, Map.of(), "S6")
        ));
        AgentExecutor executor = new AgentExecutor(new AgentActionHandlerRegistry(List.of(
                new GetDateTimeAgentActionHandler(toolRegistry, objectMapper),
                new GetWeatherAgentActionHandler(toolRegistry, objectMapper),
                new RetrieveKnowledgeAgentActionHandler(ragRetriever, objectMapper),
                new ExerciseSkillAgentActionHandler(skillRegistry),
                new MealSkillAgentActionHandler(skillRegistry),
                validate,
                synthesis
        )));

        AgentState state = executor.execute(
                "student-1",
                plan.goal(),
                List.of(ChatMessage.user("此前希望改善作息")),
                false,
                plan
        );

        assertTrue(state.statuses().values().stream()
                .allMatch(status -> status == AgentStepStatus.COMPLETED));
        assertEquals(
                List.of("S1", "S2", "S3", "S4", "S5", "S6", "S7"),
                state.observations().stream().map(AgentObservation::stepId).toList()
        );
        assertEquals(List.of("S6", "S7"), terminalOrder);
        assertTrue(synthesisSawEveryObservation.get());
        assertEquals("true", state.findObservation("S3").orElseThrow()
                .structuredData().get("matched"));
    }

    @Test
    void completeAgentExerciseWithoutMarkerContinuesThroughValidateAndSynthesize() {
        ObjectMapper objectMapper = new ObjectMapper();
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.invoke(anyString(), anyString(), any())).thenAnswer(invocation -> {
            String tool = invocation.getArgument(0);
            String modelContent = "get_current_datetime".equals(tool)
                    ? "{\"success\":true,\"result\":{\"date\":\"2026-08-27\"}}"
                    : "{\"success\":true,\"result\":{\"formatted_text\":"
                    + "\"镇江未来三日小雨转多云\"}}";
            return new ToolRegistry.Invocation(
                    true,
                    ToolResult.text(modelContent),
                    modelContent);
        });

        ExerciseHealthAdviceSkill exerciseSkill = new ExerciseHealthAdviceSkill(
                (request, context) -> com.summercamp.project.llm.ChatOutcome.text(
                        "未来七天安排四次六十分钟训练，每次包含热身、快走、自重训练和拉伸，雨天改为室内。"));
        BotSkill mealSkill = mock(BotSkill.class);
        when(mealSkill.execute(any())).thenReturn(SkillResult.completed("每日四餐饮食安排完成"));
        SkillRegistry skillRegistry = mock(SkillRegistry.class);
        when(skillRegistry.findByName("exercise-health-advice"))
                .thenReturn(Optional.of(exerciseSkill));
        when(skillRegistry.findByName("muscle-gain-meal-plan"))
                .thenReturn(Optional.of(mealSkill));

        AgentPlan plan = new AgentPlan(COMPLETE_HEALTH_GOAL, List.of(
                step("datetime", AgentAction.GET_DATETIME,
                        Map.of("timezone", "Asia/Shanghai")),
                step("weather", AgentAction.GET_WEATHER,
                        Map.of("location", "镇江", "period", "THREE_DAYS"), "datetime"),
                step("exercise", AgentAction.RUN_EXERCISE_SKILL, Map.of(), "weather"),
                step("meal", AgentAction.RUN_MEAL_SKILL, Map.of(), "datetime"),
                step("validate", AgentAction.VALIDATE, Map.of(), "exercise", "meal"),
                step("synthesis", AgentAction.SYNTHESIZE, Map.of(), "validate")
        ));
        AgentExecutor executor = new AgentExecutor(new AgentActionHandlerRegistry(List.of(
                new GetDateTimeAgentActionHandler(toolRegistry, objectMapper),
                new GetWeatherAgentActionHandler(toolRegistry, objectMapper),
                new ExerciseSkillAgentActionHandler(skillRegistry),
                new MealSkillAgentActionHandler(skillRegistry),
                new ValidateActionHandler(),
                new SynthesizeActionHandler(
                        new AgentSynthesisContextBuilder(),
                        (goal, context) -> structuredSevenDayResult())
        )));
        AgentOrchestrator orchestrator = new AgentOrchestrator(goal -> plan, executor);

        AgentRunResult result = orchestrator.run(
                new AgentRunRequest("student-1", COMPLETE_HEALTH_GOAL, List.of(), false));

        assertEquals(AgentRunResult.Status.COMPLETED, result.status());
        assertEquals(AgentStepStatus.COMPLETED, result.state().statusOf("exercise"));
        assertEquals(AgentStepStatus.COMPLETED, result.state().statusOf("validate"));
        assertEquals(AgentStepStatus.COMPLETED, result.state().statusOf("synthesis"));
        assertEquals(
                ValidateActionHandler.VALIDATION_PASSED,
                result.state().findObservation("validate").orElseThrow()
                        .structuredData().get("code"));
        assertEquals(sevenDayFinalAnswer().strip(), result.reply());
    }

    private String sevenDayFinalAnswer() {
        return """
                8月27日（周四）：训练。
                8月28日（周五）：恢复。
                8月29日（周六）：训练。
                8月30日（周日）：未获取实时天气，建议当天查看天气。
                8月31日（周一）：训练。
                9月1日（周二）：恢复。
                9月2日（周三）：训练。
                """;
    }

    private AgentSynthesisResult structuredSevenDayResult() {
        List<LocalDate> dates = List.of(
                LocalDate.parse("2026-08-27"),
                LocalDate.parse("2026-08-29"),
                LocalDate.parse("2026-08-31"),
                LocalDate.parse("2026-09-02"));
        return AgentSynthesisResult.parsed(new AgentSynthesisEnvelope(
                sevenDayFinalAnswer(),
                AgentTrainingAudit.complete(dates, Map.of(
                        dates.get(0), 60,
                        dates.get(1), 60,
                        dates.get(2), 60,
                        dates.get(3), 60))));
    }

    private AgentStep step(
            String id,
            AgentAction action,
            Map<String, String> inputs,
            String... dependencies
    ) {
        return new AgentStep(
                id,
                action,
                "execute " + id,
                "test real adapter chain",
                List.of(dependencies),
                inputs
        );
    }
}
