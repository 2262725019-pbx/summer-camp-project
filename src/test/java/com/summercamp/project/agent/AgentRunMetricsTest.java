package com.summercamp.project.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summercamp.project.rag.RagContext;
import com.summercamp.project.rag.RagDocument;
import com.summercamp.project.skill.BotSkill;
import com.summercamp.project.skill.SkillContext;
import com.summercamp.project.skill.SkillRegistry;
import com.summercamp.project.skill.SkillResult;
import com.summercamp.project.tool.BotTool;
import com.summercamp.project.tool.ToolContext;
import com.summercamp.project.tool.ToolDefinition;
import com.summercamp.project.tool.ToolRegistry;
import com.summercamp.project.tool.ToolResult;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AgentRunMetricsTest {
    private static final String GOAL = "请结合天气制定运动和饮食健康计划";
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void capturesHappyPathIncludingTwoRagQueriesAndTerminalCounts() {
        ToolRegistry tools = tools();
        AtomicReference<String> synthesisContext = new AtomicReference<>();
        AgentOrchestrator orchestrator = orchestrator(tools, synthesisContext);
        AgentRunMetricsCollector collector = new AgentRunMetricsCollector();

        AgentRunResult result = orchestrator.run(request(), collector);

        AgentRunMetricsSnapshot metrics = collector.snapshot();
        assertEquals(AgentRunResult.Status.COMPLETED, result.status());
        assertEquals(10, metrics.planStepCount());
        assertEquals(10, metrics.executedStepCount());
        assertEquals(10, metrics.completedStepCount());
        assertEquals(0, metrics.failedStepCount());
        assertEquals(0, metrics.skippedStepCount());
        assertEquals(3, metrics.llmRequestCount());
        assertEquals(1, metrics.planningLlmRequestCount());
        assertEquals(1, metrics.synthesisLlmRequestCount());
        assertEquals(1, metrics.skillLlmRequestCount());
        assertEquals(4, metrics.toolCallCount());
        assertEquals(1, metrics.weatherToolCallCount());
        assertEquals(1, metrics.weatherReuseEligibleCount());
        assertEquals(1, metrics.weatherReuseAppliedCount());
        assertEquals(1, metrics.calculateToolCallCount());
        assertEquals(1, metrics.todoToolCallCount());
        assertEquals(1, metrics.dateTimeToolCallCount());
        assertEquals(2, metrics.ragQueryCount());
        assertEquals(2, metrics.skillCallCount());
        assertEquals(1, metrics.exerciseSkillCallCount());
        assertTrue(metrics.exerciseSkillDurationMs() >= 0);
        assertEquals(1, metrics.exerciseSkillLlmRequestCount());
        assertEquals(1, metrics.mealSkillCallCount());
        assertEquals(synthesisContext.get().length(), metrics.synthesisContextChars());
        assertEquals(metrics.synthesisContextChars(),
                metrics.synthesisMetadataChars()
                        + metrics.synthesisOriginalGoalChars()
                        + metrics.synthesisDatetimeChars()
                        + metrics.synthesisWeatherChars()
                        + metrics.synthesisExerciseChars()
                        + metrics.synthesisMealChars()
                        + metrics.synthesisRagChars()
                        + metrics.synthesisTodoChars()
                        + metrics.synthesisValidateChars()
                        + metrics.synthesisCalculateChars());
        assertTrue(metrics.synthesisMetadataChars() > 0);
        assertTrue(metrics.synthesisOriginalGoalChars() > 0);
        assertTrue(metrics.synthesisDatetimeChars() > 0);
        assertTrue(metrics.synthesisWeatherChars() > 0);
        assertTrue(metrics.synthesisExerciseChars() > 0);
        assertTrue(metrics.synthesisMealChars() > 0);
        assertTrue(metrics.synthesisRagChars() > 0);
        assertTrue(metrics.synthesisTodoChars() > 0);
        assertTrue(metrics.synthesisValidateChars() > 0);
        assertTrue(metrics.synthesisCalculateChars() > 0);
        assertEquals(GOAL.length(), metrics.plannerGoalChars());
        assertTrue(metrics.plannerInstructionChars() > 0);
        assertTrue(metrics.promptChars() > 0);
        assertTrue(metrics.responseChars() > 0);
        assertTrue(metrics.contextChars() > 0);
        assertTrue(metrics.agentRunDurationMs() >= 0);
        assertTrue(metrics.plannerDurationMs() >= 0);
        assertTrue(metrics.executorDurationMs() >= 0);
        assertTrue(metrics.synthesisDurationMs() >= 0);
        assertEquals(1, metrics.finalValidationAttemptCount());
        assertEquals(0, metrics.finalValidationFailureCount());
        assertEquals(0, metrics.synthesisRepairTriggeredCount());
        assertEquals(0, metrics.synthesisRepairSucceededCount());
    }

    @Test
    void planningFailureReturnsPartialMetricsWithoutChangingRunResultContract() {
        AgentPlanner failingPlanner = new AgentPlanner() {
            @Override
            public AgentPlan plan(String goal) {
                throw new AgentPlanningException("provider unavailable");
            }

            @Override
            public AgentPlan plan(String goal, AgentRunMetrics metrics) {
                metrics.withLlmPhase(AgentRunMetrics.LlmPhase.PLANNING)
                        .recordProviderRequest(12, 8);
                throw new AgentPlanningException("provider unavailable");
            }
        };
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                failingPlanner,
                new AgentExecutor(new AgentActionHandlerRegistry(List.of())));
        AgentRunMetricsCollector collector = new AgentRunMetricsCollector();

        AgentRunResult result = orchestrator.run(request(), collector);

        AgentRunMetricsSnapshot metrics = collector.snapshot();
        assertEquals(AgentRunResult.Status.FAILED, result.status());
        assertEquals(1, metrics.llmRequestCount());
        assertEquals(1, metrics.planningLlmRequestCount());
        assertEquals(0, metrics.planStepCount());
        assertEquals(0, metrics.executedStepCount());
        assertTrue(metrics.agentRunDurationMs() >= 0);
        assertEquals(4, AgentRunResult.class.getRecordComponents().length);
    }

    @Test
    void executorFailureReturnsPlanAndPartialTerminalMetrics() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                planner(),
                new AgentExecutor(new AgentActionHandlerRegistry(List.of())));
        AgentRunMetricsCollector collector = new AgentRunMetricsCollector();

        AgentRunResult result = orchestrator.run(request(), collector);

        AgentRunMetricsSnapshot metrics = collector.snapshot();
        assertEquals(AgentRunResult.Status.FAILED, result.status());
        assertEquals(10, metrics.planStepCount());
        assertEquals(1, metrics.executedStepCount());
        assertEquals(0, metrics.completedStepCount());
        assertEquals(1, metrics.failedStepCount());
        assertEquals(0, metrics.skippedStepCount());
        assertEquals(1, metrics.planningLlmRequestCount());
    }

    @Test
    void failedDependencyProducesFailedAndSkippedTerminalCounts() {
        AgentPlan plan = terminalCountPlan();
        AgentActionHandler failingDate = handler(
                AgentAction.GET_DATETIME,
                step -> new AgentObservation(step.id(), false, "date failed"));
        AgentActionHandler successfulWeather = handler(
                AgentAction.GET_WEATHER,
                step -> new AgentObservation(step.id(), true, "weather ok"));
        AgentActionHandler successfulRag = handler(
                AgentAction.RETRIEVE_KNOWLEDGE,
                step -> new AgentObservation(step.id(), true, "rag ok"));
        AgentExecutor executor = new AgentExecutor(new AgentActionHandlerRegistry(List.of(
                failingDate, successfulWeather, successfulRag)));
        AgentOrchestrator orchestrator = new AgentOrchestrator(goal -> plan, executor);
        AgentRunMetricsCollector collector = new AgentRunMetricsCollector();

        AgentRunResult result = orchestrator.run(request(), collector);

        AgentRunMetricsSnapshot metrics = collector.snapshot();
        assertEquals(AgentRunResult.Status.FAILED, result.status());
        assertEquals(5, metrics.planStepCount());
        assertEquals(3, metrics.executedStepCount());
        assertEquals(2, metrics.completedStepCount());
        assertEquals(1, metrics.failedStepCount());
        assertEquals(2, metrics.skippedStepCount());
    }

    @Test
    void exerciseWithoutAgentWeatherKeepsNormalToolCallingRound() {
        ToolRegistry tools = tools();
        SkillRegistry skills = new SkillRegistry(List.of(exerciseSkill(tools)));
        AgentExecutor executor = new AgentExecutor(new AgentActionHandlerRegistry(List.of(
                new ExerciseSkillAgentActionHandler(skills))));
        AgentPlan plan = new AgentPlan("制定镇江运动计划", List.of(new AgentStep(
                "exercise",
                AgentAction.RUN_EXERCISE_SKILL,
                "制定运动计划",
                "测试普通 Skill 调用",
                List.of(),
                java.util.Map.of())));
        AgentRunMetricsCollector collector = new AgentRunMetricsCollector();

        AgentState state = executor.execute(
                "user-a",
                plan.goal(),
                List.of(),
                false,
                plan,
                AgentRunMetrics.observe(collector));

        AgentRunMetricsSnapshot metrics = collector.snapshot();
        assertEquals(AgentStepStatus.COMPLETED, state.statusOf("exercise"));
        assertEquals(2, metrics.skillLlmRequestCount());
        assertEquals(2, metrics.exerciseSkillLlmRequestCount());
        assertTrue(metrics.exerciseSkillDurationMs() >= 0);
        assertEquals(1, metrics.weatherToolCallCount());
        assertEquals(0, metrics.weatherReuseEligibleCount());
        assertEquals(0, metrics.weatherReuseAppliedCount());
    }

    @Test
    void realIndependentPlanShapeUsesOneWeatherCallThroughSafePredecessorReuse() {
        ToolRegistry tools = tools();
        SkillRegistry skills = new SkillRegistry(List.of(
                exerciseSkill(tools),
                completedSkill("muscle-gain-meal-plan", "饮食计划")));
        AgentPlan plan = new AgentPlan("制定健康计划", List.of(
                new AgentStep(
                        "S1", AgentAction.GET_WEATHER, "查询天气", "适配运动", List.of(),
                        java.util.Map.of("location", "镇江", "period", "THREE_DAYS")),
                new AgentStep(
                        "S2", AgentAction.RUN_EXERCISE_SKILL, "运动计划", "健康", List.of(),
                        java.util.Map.of()),
                new AgentStep(
                        "S3", AgentAction.RUN_MEAL_SKILL, "饮食计划", "健康", List.of(),
                        java.util.Map.of()),
                new AgentStep(
                        "S4", AgentAction.CREATE_TODO, "创建待办", "执行", List.of(),
                        java.util.Map.of("item", "执行健康计划")),
                new AgentStep(
                        "S5", AgentAction.GET_DATETIME, "查询日期", "安排", List.of(),
                        java.util.Map.of("timezone", "Asia/Shanghai")),
                new AgentStep(
                        "S6", AgentAction.VALIDATE, "验证", "检查", List.of(), java.util.Map.of()),
                new AgentStep(
                        "S7", AgentAction.SYNTHESIZE, "汇总", "输出", List.of(), java.util.Map.of())));
        AgentExecutor executor = new AgentExecutor(new AgentActionHandlerRegistry(List.of(
                new GetWeatherAgentActionHandler(tools, objectMapper),
                new ExerciseSkillAgentActionHandler(skills),
                new MealSkillAgentActionHandler(skills),
                new CreateTodoAgentActionHandler(tools, objectMapper),
                new GetDateTimeAgentActionHandler(tools, objectMapper),
                handler(AgentAction.VALIDATE,
                        step -> new AgentObservation(step.id(), true, "valid")),
                handler(AgentAction.SYNTHESIZE,
                        step -> new AgentObservation(step.id(), true, "final")))));
        AgentRunMetricsCollector collector = new AgentRunMetricsCollector();

        AgentState state = executor.execute(
                "user-a",
                plan.goal(),
                List.of(),
                false,
                plan,
                AgentRunMetrics.observe(collector));

        AgentRunMetricsSnapshot metrics = collector.snapshot();
        assertTrue(state.statuses().values().stream()
                .allMatch(status -> status == AgentStepStatus.COMPLETED));
        assertEquals(1, metrics.weatherToolCallCount());
        assertEquals(1, metrics.skillLlmRequestCount());
        assertEquals(1, metrics.exerciseSkillLlmRequestCount());
        assertTrue(metrics.exerciseSkillDurationMs() >= 0);
        assertEquals(1, metrics.weatherReuseEligibleCount());
        assertEquals(1, metrics.weatherReuseAppliedCount());
    }

    @Test
    void snapshotIsAnImmutablePrimitiveValue() {
        AgentRunMetricsSnapshot snapshot = new AgentRunMetricsCollector().snapshot();

        assertTrue(snapshot.getClass().isRecord());
        assertTrue(Arrays.stream(snapshot.getClass().getRecordComponents())
                .allMatch(component -> component.getType().isPrimitive()));
    }

    @Test
    void concurrentIncrementsDoNotLoseCounts() throws Exception {
        int taskCount = 16;
        int incrementsPerTask = 2_000;
        AgentRunMetricsCollector collector = new AgentRunMetricsCollector();
        AgentRunMetrics metrics = AgentRunMetrics.observe(collector);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int task = 0; task < taskCount; task++) {
                futures.add(executor.submit(() -> {
                    for (int increment = 0; increment < incrementsPerTask; increment++) {
                        metrics.recordExecutedStep();
                        metrics.recordToolCall("get_weather");
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        }

        long expected = (long) taskCount * incrementsPerTask;
        assertEquals(expected, collector.snapshot().executedStepCount());
        assertEquals(expected, collector.snapshot().toolCallCount());
        assertEquals(expected, collector.snapshot().weatherToolCallCount());
    }

    @Test
    void collectorFailureDoesNotChangeBusinessResult() {
        FailingCollector collector = new FailingCollector();
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                planner(),
                executor(tools(), new AtomicReference<>()),
                () -> collector);

        AgentRunResult result = orchestrator.run(request());

        assertEquals(AgentRunResult.Status.COMPLETED, result.status());
        assertFalse(result.reply().isBlank());
        assertEquals(AgentRunMetricsSnapshot.empty(), AgentRunMetrics.observe(collector).snapshot());
    }

    private AgentRunRequest request() {
        return new AgentRunRequest("user-a", GOAL, List.of(), false);
    }

    private AgentOrchestrator orchestrator(
            ToolRegistry tools,
            AtomicReference<String> synthesisContext
    ) {
        return new AgentOrchestrator(planner(), executor(tools, synthesisContext));
    }

    private AgentPlanner planner() {
        AgentPlanningClient client = new AgentPlanningClient() {
            @Override
            public String generatePlan(String goal, String instructions) {
                return rawPlan();
            }

            @Override
            public String generatePlan(
                    String goal,
                    String instructions,
                    AgentRunMetrics metrics
            ) {
                String rawPlan = rawPlan();
                metrics.recordProviderRequest(
                        goal.length() + instructions.length(),
                        goal.length() + instructions.length());
                metrics.recordProviderResponse(rawPlan.length());
                return rawPlan;
            }
        };
        return new LlmAgentPlanner(client, objectMapper);
    }

    private AgentExecutor executor(
            ToolRegistry tools,
            AtomicReference<String> synthesisContext
    ) {
        SkillRegistry skills = new SkillRegistry(List.of(
                exerciseSkill(tools),
                completedSkill("muscle-gain-meal-plan", "饮食计划")));
        List<AgentActionHandler> handlers = new ArrayList<>();
        handlers.add(new GetDateTimeAgentActionHandler(tools, objectMapper));
        handlers.add(new GetWeatherAgentActionHandler(tools, objectMapper));
        handlers.add(new RetrieveKnowledgeAgentActionHandler(query -> new RagContext(
                List.of(new RagContext.Hit(
                        new RagDocument("health", "健康知识", List.of("健康"), "保持规律作息"),
                        1)),
                "保持规律作息"), objectMapper));
        handlers.add(new ExerciseSkillAgentActionHandler(skills));
        handlers.add(new MealSkillAgentActionHandler(skills));
        handlers.add(new CalculateAgentActionHandler(tools, objectMapper));
        handlers.add(new CreateTodoAgentActionHandler(tools, objectMapper));
        handlers.add(new ValidateActionHandler());
        handlers.add(new SynthesizeActionHandler(
                new AgentSynthesisContextBuilder(),
                synthesisClient(synthesisContext)));
        return new AgentExecutor(new AgentActionHandlerRegistry(handlers));
    }

    private AgentSynthesisClient synthesisClient(AtomicReference<String> synthesisContext) {
        return new AgentSynthesisClient() {
            @Override
            public AgentSynthesisResult synthesize(String originalGoal, String observationContext) {
                return AgentSynthesisResult.answerOnly("最终健康计划");
            }

            @Override
            public AgentSynthesisResult synthesize(
                    String originalGoal,
                    String observationContext,
                    AgentRunMetrics metrics
            ) {
                synthesisContext.set(observationContext);
                metrics.recordProviderRequest(
                        observationContext.length(), observationContext.length());
                metrics.recordProviderResponse(8);
                return AgentSynthesisResult.answerOnly("最终健康计划");
            }
        };
    }

    private BotSkill exerciseSkill(ToolRegistry tools) {
        return new BotSkill() {
            @Override
            public String name() {
                return "exercise-health-advice";
            }

            @Override
            public int matchScore(String text) {
                return 1;
            }

            @Override
            public SkillResult execute(SkillContext context) {
                context.metrics().recordProviderRequest(20, 16);
                context.metrics().recordProviderResponse(10);
                if (context.trustedContext().weatherObservation().isEmpty()) {
                    tools.invoke("get_weather", "{}", new ToolContext(
                            context.userId(),
                            context.text(),
                            context.history(),
                            context.metrics()));
                    context.metrics().recordProviderRequest(20, 16);
                    context.metrics().recordProviderResponse(10);
                }
                return SkillResult.completed("运动计划");
            }
        };
    }

    private BotSkill completedSkill(String name, String reply) {
        return new BotSkill() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public int matchScore(String text) {
                return 1;
            }

            @Override
            public SkillResult execute(SkillContext context) {
                return SkillResult.completed(reply);
            }
        };
    }

    private ToolRegistry tools() {
        return new ToolRegistry(List.of(
                tool("get_current_datetime"),
                tool("get_weather"),
                tool("calculate"),
                tool("add_todo")), objectMapper);
    }

    private AgentActionHandler handler(
            AgentAction action,
            java.util.function.Function<AgentStep, AgentObservation> execution
    ) {
        return new AgentActionHandler() {
            @Override
            public AgentAction action() {
                return action;
            }

            @Override
            public AgentObservation execute(AgentStep step, AgentExecutionContext context) {
                return execution.apply(step);
            }
        };
    }

    private AgentPlan terminalCountPlan() {
        return new AgentPlan(GOAL, List.of(
                new AgentStep("date", AgentAction.GET_DATETIME, "查询日期", "日期", List.of()),
                new AgentStep(
                        "weather", AgentAction.GET_WEATHER, "查询天气", "天气", List.of(),
                        java.util.Map.of("location", "镇江", "period", "TODAY")),
                new AgentStep(
                        "rag", AgentAction.RETRIEVE_KNOWLEDGE, "查询知识", "知识", List.of(),
                        java.util.Map.of("query", "健康")),
                new AgentStep(
                        "validate", AgentAction.VALIDATE, "校验", "校验",
                        List.of("date", "weather", "rag")),
                new AgentStep(
                        "synthesis", AgentAction.SYNTHESIZE, "汇总", "汇总",
                        List.of("validate"))));
    }

    private BotTool tool(String name) {
        return new BotTool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition(
                        name,
                        "test tool",
                        objectMapper.createObjectNode().put("type", "object"));
            }

            @Override
            public ToolResult execute(JsonNode arguments, ToolContext context) {
                return ToolResult.text("ok");
            }
        };
    }

    private String rawPlan() {
        return """
                {
                  "goal":"请结合天气制定运动和饮食健康计划",
                  "steps":[
                    {"id":"date","action":"GET_DATETIME","description":"查询日期","reason":"安排日期","dependsOn":[],"inputs":{"timezone":"Asia/Shanghai"}},
                    {"id":"weather","action":"GET_WEATHER","description":"查询天气","reason":"适配运动","dependsOn":[],"inputs":{"location":"镇江","period":"THREE_DAYS"}},
                    {"id":"rag-one","action":"RETRIEVE_KNOWLEDGE","description":"检索运动知识","reason":"补充依据","dependsOn":[],"inputs":{"query":"健康运动"}},
                    {"id":"rag-two","action":"RETRIEVE_KNOWLEDGE","description":"检索饮食知识","reason":"补充依据","dependsOn":[],"inputs":{"query":"健康饮食"}},
                    {"id":"exercise","action":"RUN_EXERCISE_SKILL","description":"制定运动计划","reason":"满足运动目标","dependsOn":["weather"],"inputs":{}},
                    {"id":"meal","action":"RUN_MEAL_SKILL","description":"制定饮食计划","reason":"满足饮食目标","dependsOn":[],"inputs":{}},
                    {"id":"calc","action":"CALCULATE","description":"计算指标","reason":"提供数值","dependsOn":[],"inputs":{"expression":"1+1"}},
                    {"id":"todo","action":"CREATE_TODO","description":"添加待办","reason":"便于执行","dependsOn":[],"inputs":{"item":"执行健康计划"}},
                    {"id":"validate","action":"VALIDATE","description":"校验结果","reason":"确保可靠","dependsOn":["date","weather","rag-one","rag-two","exercise","meal","calc","todo"],"inputs":{}},
                    {"id":"synthesis","action":"SYNTHESIZE","description":"汇总计划","reason":"形成回答","dependsOn":["validate"],"inputs":{}}
                  ]
                }
                """;
    }

    private static final class FailingCollector extends AgentRunMetricsCollector {
        @Override
        public void recordPlanStepCount(long count) {
            throw failure();
        }

        @Override
        public void recordLlmRequest(AgentRunMetrics.LlmPhase phase, long requestChars, long inputChars) {
            throw failure();
        }

        @Override
        public void recordToolCall(String toolName) {
            throw failure();
        }

        @Override
        public void recordRagQuery() {
            throw failure();
        }

        @Override
        public void recordSkillCall(AgentAction action) {
            throw failure();
        }

        @Override
        public void recordCompletedStep() {
            throw failure();
        }

        @Override
        public void recordSynthesisContextChars(long chars) {
            throw failure();
        }

        @Override
        public void recordSynthesisContext(AgentSynthesisContextBuilder.Breakdown breakdown) {
            throw failure();
        }

        @Override
        public AgentRunMetricsSnapshot snapshot() {
            throw failure();
        }

        private IllegalStateException failure() {
            return new IllegalStateException("metrics unavailable");
        }
    }
}
