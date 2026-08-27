package com.summercamp.project.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summercamp.project.llm.AgentProviderException;
import com.summercamp.project.llm.AgentProviderFailureCategory;
import com.summercamp.project.skill.BotSkill;
import com.summercamp.project.skill.SkillContext;
import com.summercamp.project.skill.SkillRegistry;
import com.summercamp.project.skill.SkillResult;
import com.summercamp.project.tool.ToolRegistry;
import com.summercamp.project.tool.ToolResult;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AgentResumeIntegrationTest {

    private static final String GOAL =
            "请制定未来7天天气、运动、饮食和作息健康计划，每周训练4次，每次训练60分钟";
    private static final String SUPPLEMENT = """
            性别：男
            年龄：22
            身高：175cm
            体重：70kg
            日常活动：轻度
            每周训练：4次
            每次训练：60分钟
            每日餐数：4餐
            健康确认：健康成人、无食物过敏
            """;

    @Test
    void resumesWaitingMealWithoutReplanningOrRepeatingCompletedCapabilities() {
        AgentPlan plan = plan();
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger synthesisCalls = new AtomicInteger();
        EnumMap<AgentAction, AtomicInteger> calls = new EnumMap<>(AgentAction.class);
        AtomicInteger todoCalls = new AtomicInteger();
        ToolRegistry tools = mock(ToolRegistry.class);
        when(tools.invoke(eq("add_todo"), anyString(), any())).thenAnswer(invocation -> {
            todoCalls.incrementAndGet();
            return new ToolRegistry.Invocation(
                    true,
                    ToolResult.text("todo-created"),
                    "{\"success\":true,\"result\":\"todo-created\"}");
        });
        List<String> mealRequests = new ArrayList<>();
        BotSkill mealSkill = mealSkill(mealRequests);
        SkillRegistry skills = new SkillRegistry(List.of(mealSkill));

        AgentActionHandlerRegistry registry = new AgentActionHandlerRegistry(List.of(
                succeeding(AgentAction.GET_DATETIME, calls,
                        Map.of("tool", "get_current_datetime", "modelContent",
                                "{\"success\":true,\"result\":{\"date\":\"2026-08-27\"}}")),
                succeeding(AgentAction.GET_WEATHER, calls,
                        Map.of("tool", "get_weather", "location", "镇江",
                                "period", "THREE_DAYS", "modelContent",
                                "{\"success\":true,\"result\":{\"formatted_text\":\"三日天气\"}}")),
                succeeding(AgentAction.RETRIEVE_KNOWLEDGE, calls,
                        Map.of("matched", "true", "documentIds", "[]", "promptContext", "知识")),
                succeeding(AgentAction.RUN_EXERCISE_SKILL, calls,
                        Map.of("skill", "exercise-health-advice", "status", "COMPLETED",
                                "reply", "运动完成")),
                new CreateTodoAgentActionHandler(tools, new ObjectMapper()),
                new MealSkillAgentActionHandler(skills),
                new ValidateActionHandler(),
                new SynthesizeActionHandler(
                        new AgentSynthesisContextBuilder(),
                        (goal, context) -> structuredResult(
                                synthesisCalls.getAndIncrement() == 0
                                        ? validFinalAnswer().replace(
                                                "8月31日（周一）：恢复。\n", "")
                                        : validFinalAnswer()))
        ));
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                goal -> {
                    plannerCalls.incrementAndGet();
                    return plan;
                },
                new AgentExecutor(registry));

        AgentRunResult initial = orchestrator.run(
                new AgentRunRequest("user-a", GOAL, List.of(), false));

        assertEquals(AgentRunResult.Status.NEEDS_USER_INPUT, initial.status());
        assertEquals(AgentStepStatus.SKIPPED, initial.state().statusOf("validate"));
        assertEquals(AgentStepStatus.SKIPPED, initial.state().statusOf("synthesis"));
        assertEquals(AgentStepStatus.SKIPPED, initial.state().statusOf("todo"));
        PendingAgentRunStore store = new PendingAgentRunStore();
        AgentRunCheckpoint checkpoint = store.rememberInitial(
                "user-a",
                new AgentRunRequest("user-a", GOAL, List.of(), false),
                initial).orElseThrow();
        AgentRunMetricsCollector resumeMetrics = new AgentRunMetricsCollector();

        AgentRunResult resumed = orchestrator.resume(
                checkpoint,
                "user-a",
                new AgentResumeInput("meal", SUPPLEMENT, 1),
                List.of(),
                false,
                resumeMetrics);

        assertEquals(AgentRunResult.Status.COMPLETED, resumed.status());
        assertEquals(validFinalAnswer().strip(), resumed.reply());
        assertEquals(1, plannerCalls.get());
        assertEquals(2, synthesisCalls.get());
        assertEquals(1, count(calls, AgentAction.GET_DATETIME));
        assertEquals(1, count(calls, AgentAction.GET_WEATHER));
        assertEquals(1, count(calls, AgentAction.RETRIEVE_KNOWLEDGE));
        assertEquals(1, count(calls, AgentAction.RUN_EXERCISE_SKILL));
        assertEquals(1, todoCalls.get());
        assertEquals(2, mealRequests.size());
        assertTrue(mealRequests.get(1).contains(GOAL));
        assertTrue(mealRequests.get(1).contains("当前 Agent 步骤补充：生成四餐方案"));
        assertTrue(mealRequests.get(1).contains("用户最新补充：" + SUPPLEMENT.strip()));
        assertEquals(AgentStepStatus.COMPLETED, resumed.state().statusOf("meal"));
        assertEquals(AgentStepStatus.COMPLETED, resumed.state().statusOf("validate"));
        assertEquals(AgentStepStatus.COMPLETED, resumed.state().statusOf("synthesis"));

        AgentRunMetricsSnapshot metrics = resumeMetrics.snapshot();
        assertEquals(1, metrics.agentResumeCount());
        assertEquals(1, metrics.resumeAttemptCount());
        assertEquals(4, metrics.reusedCompletedStepCount());
        assertEquals(4, metrics.executedAfterResumeStepCount());
        assertEquals(0, metrics.planningLlmRequestCount());
        assertEquals(2, metrics.finalValidationAttemptCount());
        assertEquals(1, metrics.finalValidationFailureCount());
        assertEquals(1, metrics.synthesisRepairTriggeredCount());
        assertEquals(1, metrics.synthesisRepairSucceededCount());
    }

    @Test
    void resumeUsesDeterministicSynthesisDuringProviderOutageWithoutRepeatingSideEffects() {
        AgentPlan plan = plan();
        AtomicInteger plannerCalls = new AtomicInteger();
        EnumMap<AgentAction, AtomicInteger> calls = new EnumMap<>(AgentAction.class);
        AtomicInteger todoCalls = new AtomicInteger();
        ToolRegistry tools = mock(ToolRegistry.class);
        when(tools.invoke(eq("add_todo"), anyString(), any())).thenAnswer(invocation -> {
            todoCalls.incrementAndGet();
            return new ToolRegistry.Invocation(
                    true,
                    ToolResult.text("todo-created"),
                    "{\"success\":true,\"result\":\"todo-created\"}");
        });
        List<String> mealRequests = new ArrayList<>();
        SkillRegistry skills = new SkillRegistry(List.of(mealSkill(mealRequests)));
        AgentActionHandlerRegistry registry = new AgentActionHandlerRegistry(List.of(
                succeeding(AgentAction.GET_DATETIME, calls,
                        Map.of("tool", "get_current_datetime", "modelContent",
                                "{\"success\":true,\"result\":{\"date\":\"2026-08-27\"}}")),
                succeeding(AgentAction.GET_WEATHER, calls,
                        Map.of("tool", "get_weather", "location", "镇江",
                                "period", "THREE_DAYS", "modelContent",
                                "2026-08-27 晴；2026-08-28 阴；2026-08-29 小雨")),
                succeeding(AgentAction.RETRIEVE_KNOWLEDGE, calls,
                        Map.of("matched", "true", "documentIds", "[]", "promptContext", "知识")),
                succeeding(AgentAction.RUN_EXERCISE_SKILL, calls,
                        Map.of("skill", "exercise-health-advice", "status", "COMPLETED",
                                "reply", "运动完成")),
                new CreateTodoAgentActionHandler(tools, new ObjectMapper()),
                new MealSkillAgentActionHandler(skills),
                new ValidateActionHandler(),
                new SynthesizeActionHandler(
                        new AgentSynthesisContextBuilder(),
                        (goal, context) -> {
                            throw new AgentProviderException(
                                    "SYNTHESIS", AgentProviderFailureCategory.TIMEOUT, null);
                        })
        ));
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                goal -> {
                    plannerCalls.incrementAndGet();
                    return plan;
                },
                new AgentExecutor(registry));

        AgentRunResult initial = orchestrator.run(
                new AgentRunRequest("user-a", GOAL, List.of(), false));
        AgentRunCheckpoint checkpoint = new PendingAgentRunStore().rememberInitial(
                "user-a",
                new AgentRunRequest("user-a", GOAL, List.of(), false),
                initial).orElseThrow();
        AgentRunMetricsCollector resumeMetrics = new AgentRunMetricsCollector();

        AgentRunResult resumed = orchestrator.resume(
                checkpoint,
                "user-a",
                new AgentResumeInput("meal", SUPPLEMENT, 1),
                List.of(),
                false,
                resumeMetrics);

        assertEquals(AgentRunResult.Status.COMPLETED, resumed.status());
        assertEquals(1, plannerCalls.get());
        assertEquals(1, count(calls, AgentAction.GET_DATETIME));
        assertEquals(1, count(calls, AgentAction.GET_WEATHER));
        assertEquals(1, count(calls, AgentAction.RETRIEVE_KNOWLEDGE));
        assertEquals(1, count(calls, AgentAction.RUN_EXERCISE_SKILL));
        assertEquals(1, todoCalls.get());
        assertEquals(2, mealRequests.size());
        assertEquals(1, resumeMetrics.snapshot().deterministicSynthesisFallbackCount());
        assertEquals(0, resumeMetrics.snapshot().planningLlmRequestCount());
        assertEquals(4, resumeMetrics.snapshot().reusedCompletedStepCount());
        assertEquals(4, resumeMetrics.snapshot().executedAfterResumeStepCount());
        assertTrue(resumed.reply().contains("8月27日（周四）"));
        assertTrue(resumed.reply().contains("9月2日（周三）"));
    }

    private BotSkill mealSkill(List<String> requests) {
        return new BotSkill() {
            @Override
            public String name() {
                return "muscle-gain-meal-plan";
            }

            @Override
            public int matchScore(String text) {
                return 0;
            }

            @Override
            public SkillResult execute(SkillContext context) {
                requests.add(context.text());
                return requests.size() == 1
                        ? SkillResult.waitingInput("请补充个人资料")
                        : SkillResult.completed("四餐方案完成");
            }
        };
    }

    private AgentActionHandler succeeding(
            AgentAction action,
            Map<AgentAction, AtomicInteger> calls,
            Map<String, String> data
    ) {
        return new AgentActionHandler() {
            @Override
            public AgentAction action() {
                return action;
            }

            @Override
            public AgentObservation execute(AgentStep step, AgentExecutionContext context) {
                calls.computeIfAbsent(action, ignored -> new AtomicInteger()).incrementAndGet();
                return new AgentObservation(step.id(), true, action + " complete", data);
            }
        };
    }

    private int count(Map<AgentAction, AtomicInteger> calls, AgentAction action) {
        return calls.getOrDefault(action, new AtomicInteger()).get();
    }

    private AgentPlan plan() {
        return new AgentPlan(GOAL, List.of(
                step("datetime", AgentAction.GET_DATETIME, Map.of("timezone", "Asia/Shanghai")),
                step("weather", AgentAction.GET_WEATHER,
                        Map.of("location", "镇江", "period", "THREE_DAYS"), "datetime"),
                step("rag", AgentAction.RETRIEVE_KNOWLEDGE,
                        Map.of("query", "大学生健康生活")),
                step("exercise", AgentAction.RUN_EXERCISE_SKILL, Map.of(), "weather"),
                step("meal", AgentAction.RUN_MEAL_SKILL,
                        Map.of("request", "生成四餐方案")),
                step("todo", AgentAction.CREATE_TODO,
                        Map.of("item", "执行健康计划"), "meal"),
                step("validate", AgentAction.VALIDATE, Map.of(),
                        "rag", "exercise", "todo"),
                step("synthesis", AgentAction.SYNTHESIZE, Map.of(), "validate")
        ));
    }

    private String validFinalAnswer() {
        return """
                8月27日（周四）：训练。
                8月28日（周五）：恢复。
                8月29日（周六）：训练。
                8月30日（周日）：恢复。
                8月31日（周一）：恢复。
                9月1日（周二）：训练。
                9月2日（周三）：训练。
                """;
    }

    private AgentSynthesisResult structuredResult(String answer) {
        List<LocalDate> dates = List.of(
                LocalDate.parse("2026-08-27"),
                LocalDate.parse("2026-08-29"),
                LocalDate.parse("2026-09-01"),
                LocalDate.parse("2026-09-02"));
        return AgentSynthesisResult.parsed(new AgentSynthesisEnvelope(
                answer,
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
        return new AgentStep(id, action, "执行 " + id, "测试恢复", List.of(dependencies), inputs);
    }
}
