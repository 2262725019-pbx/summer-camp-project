package com.summercamp.project.agent;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summercamp.project.llm.AgentProviderException;
import com.summercamp.project.llm.AgentProviderFailureCategory;
import com.summercamp.project.rag.RagRetriever;
import com.summercamp.project.skill.SkillRegistry;
import com.summercamp.project.tool.ToolRegistry;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class LlmAgentPlannerTest {
    private static final String SEVEN_DAY_GOAL = "制定未来7天大学生增肌健康生活方案";
    private static final String DAILY_GOAL = "帮我安排今天的规律作息和轻量运动";
    private static final String REAL_VALIDATION_GOAL =
            "请帮我制定未来7天的大学生健康生活规划，兼顾天气、运动、饮食和作息。";
    private static final String PROVIDER_OUTAGE_GOAL = """
            请帮我制定未来7天的大学生健康生活规划。
            所在地：镇江市
            兼顾天气、运动、饮食和作息。
            """;

    @Test
    void createsDifferentAutonomousPlansForDifferentGoals() {
        FakeAgentPlanningClient client = new FakeAgentPlanningClient()
                .respondTo(SEVEN_DAY_GOAL, sevenDayPlanJson())
                .respondTo(DAILY_GOAL, smallPlanJson(DAILY_GOAL));
        LlmAgentPlanner planner = planner(client);

        AgentPlan sevenDayPlan = planner.plan(SEVEN_DAY_GOAL);
        AgentPlan dailyPlan = planner.plan(DAILY_GOAL);

        assertEquals(List.of(
                AgentAction.GET_DATETIME,
                AgentAction.GET_WEATHER,
                AgentAction.RETRIEVE_KNOWLEDGE,
                AgentAction.RUN_EXERCISE_SKILL,
                AgentAction.RUN_MEAL_SKILL,
                AgentAction.VALIDATE,
                AgentAction.SYNTHESIZE), actions(sevenDayPlan));
        assertEquals(List.of(
                AgentAction.GET_DATETIME,
                AgentAction.RETRIEVE_KNOWLEDGE,
                AgentAction.RUN_EXERCISE_SKILL,
                AgentAction.VALIDATE,
                AgentAction.SYNTHESIZE), actions(dailyPlan));
        assertNotEquals(sevenDayPlan.steps().size(), dailyPlan.steps().size());
        assertEquals(
                Map.of("location", "镇江", "period", "THREE_DAYS"),
                sevenDayPlan.steps().get(1).inputs()
        );
        assertNotEquals(sevenDayPlan.steps().get(2).inputs(), dailyPlan.steps().get(1).inputs());
        assertEquals(List.of(SEVEN_DAY_GOAL, DAILY_GOAL), client.requestedGoals());
        assertTrue(sevenDayPlan.steps().stream().allMatch(step -> step.status() == AgentStepStatus.PENDING));
        assertTrue(dailyPlan.steps().stream().allMatch(step -> step.status() == AgentStepStatus.PENDING));
    }

    @Test
    void repairsInvalidJsonOnce() {
        FakeAgentPlanningClient client = new FakeAgentPlanningClient()
                .respondTo(DAILY_GOAL, "this is not json", smallPlanJson(DAILY_GOAL));

        AgentPlan plan = planner(client).plan(DAILY_GOAL);

        assertEquals(5, plan.steps().size());
        assertEquals(2, client.requests().size());
        assertFalse(client.requests().getFirst().instructions().contains("上一次输出未通过"));
        assertTrue(client.requests().getLast().instructions().contains("上一次输出未通过"));
        assertTrue(client.requests().getLast().instructions()
                .contains("JSON_PARSER:JSON_PARSE_FAILED"));
    }

    @Test
    void repairsUnknownAction() {
        String unknownAction = smallPlanJson(DAILY_GOAL)
                .replace("RUN_EXERCISE_SKILL", "UNKNOWN_ACTION");
        FakeAgentPlanningClient client = new FakeAgentPlanningClient()
                .respondTo(DAILY_GOAL, unknownAction, smallPlanJson(DAILY_GOAL));

        AgentPlan plan = planner(client).plan(DAILY_GOAL);

        assertEquals(AgentAction.RUN_EXERCISE_SKILL, plan.steps().get(2).action());
        assertEquals(2, client.requests().size());
        assertTrue(client.requests().getLast().instructions()
                .contains("JSON_PARSER:UNKNOWN_ACTION"));
    }

    @Test
    void repairsDependencyCycleRejectedByValidator() {
        String cycle = smallPlanJson(DAILY_GOAL)
                .replaceFirst("\"dependsOn\":\\[\\]", "\"dependsOn\":[\"S3\"]");
        FakeAgentPlanningClient client = new FakeAgentPlanningClient()
                .respondTo(DAILY_GOAL, cycle, smallPlanJson(DAILY_GOAL));

        AgentPlan plan = planner(client).plan(DAILY_GOAL);

        assertEquals(5, plan.steps().size());
        assertEquals(2, client.requests().size());
        assertTrue(client.requests().getLast().instructions()
                .contains("PLAN_VALIDATOR:DEPENDENCY_CYCLE"));
    }

    @Test
    void repairsPlanWithoutSynthesize() {
        String noSynthesize = smallPlanJson(DAILY_GOAL)
                .replace("SYNTHESIZE", "CREATE_TODO");
        FakeAgentPlanningClient client = new FakeAgentPlanningClient()
                .respondTo(DAILY_GOAL, noSynthesize, smallPlanJson(DAILY_GOAL));

        AgentPlan plan = planner(client).plan(DAILY_GOAL);

        assertEquals(AgentAction.SYNTHESIZE, plan.steps().getLast().action());
        assertEquals(2, client.requests().size());
        assertTrue(client.requests().getLast().instructions()
                .contains("PLAN_VALIDATOR:SYNTHESIZE_MISSING"));
    }

    @Test
    void throwsAfterExactlyOneRepairWhenBothOutputsAreInvalid() {
        FakeAgentPlanningClient client = new FakeAgentPlanningClient()
                .respondTo(DAILY_GOAL, "not-json", "[]");

        AgentPlanningException exception = assertThrows(
                AgentPlanningException.class,
                () -> planner(client).plan(DAILY_GOAL));

        assertTrue(exception.getMessage().contains("after one repair attempt"));
        assertEquals(2, client.requests().size());
        assertEquals(1, LlmAgentPlanner.MAX_REPAIR_ATTEMPTS);
    }

    @Test
    void acceptsNonExactModelGoalWithoutRepairAndUsesCanonicalGoal() {
        String changedGoal = smallPlanJson(DAILY_GOAL).replace(DAILY_GOAL, "另一个目标");
        FakeAgentPlanningClient client = new FakeAgentPlanningClient()
                .respondTo(DAILY_GOAL, changedGoal);

        AgentPlan plan = planner(client).plan(DAILY_GOAL);

        assertEquals(DAILY_GOAL, plan.goal());
        assertEquals(1, client.requests().size());
    }

    @Test
    void canonicalizesLongMultilineProductionStyleGoal() {
        String requestedGoal = """
                /agent 请帮我制定未来7天的大学生健康生活规划。
                所在地：镇江市
                性别：男
                年龄：21
                身高：175cm
                体重：70kg
                日常活动：轻度
                每周训练：4次
                每次训练：40分钟
                每日餐数：4餐
                健康确认：健康成人、无食物过敏
                目前无明显身体不适。
                运动目标是增肌，喜欢快走和自重训练。
                希望未来7天兼顾饮食、运动、作息，并根据近期天气调整户外安排，
                请直接给我一份完整可执行方案。
                """.strip();
        String modelPlan = sevenDayPlanJson()
                .replace("制定未来7天大学生增肌健康生活方案", "未来7天大学生健康生活规划");
        FakeAgentPlanningClient client = new FakeAgentPlanningClient()
                .respondTo(requestedGoal, modelPlan);

        AgentPlan plan = planner(client).plan(requestedGoal);

        assertEquals(requestedGoal, plan.goal());
        assertEquals(requestedGoal, client.requests().getFirst().goal());
        assertEquals(1, client.requests().size());
    }

    @Test
    void repairsPlanThatMissesExplicitExerciseRequirement() {
        String goal = "未来7天兼顾饮食、运动、作息和天气";
        FakeAgentPlanningClient client = new FakeAgentPlanningClient()
                .respondTo(goal, missingExercisePlanJson(goal), sevenDayPlanJson());

        AgentPlan plan = planner(client).plan(goal);

        assertTrue(actions(plan).contains(AgentAction.RUN_EXERCISE_SKILL));
        assertEquals(2, client.requests().size());
        assertTrue(client.requests().getLast().instructions()
                .contains(GoalCoverageValidator.MISSING_REQUIRED_EXERCISE_ACTION));
    }

    @Test
    void repairsTemporalPlanMissingDatetimeExactlyOnce() {
        FakeAgentPlanningClient client = new FakeAgentPlanningClient()
                .respondTo(SEVEN_DAY_GOAL, missingDatetimePlanJson(), sevenDayPlanJson());

        AgentPlan plan = planner(client).plan(SEVEN_DAY_GOAL);

        assertEquals(1, actions(plan).stream()
                .filter(action -> action == AgentAction.GET_DATETIME)
                .count());
        assertEquals(2, client.requests().size());
        assertTrue(client.requests().getLast().instructions()
                .contains(GoalCoverageValidator.MISSING_REQUIRED_DATETIME_ACTION));
    }

    @Test
    void reproducesRealMissingDatetimeAndRepairsWithDynamicRequiredActions() {
        FakeAgentPlanningClient client = new FakeAgentPlanningClient()
                .respondTo(
                        REAL_VALIDATION_GOAL,
                        missingDatetimePlanJson(REAL_VALIDATION_GOAL),
                        sevenDayPlanJson()
                );

        AgentPlan plan = planner(client).plan(REAL_VALIDATION_GOAL);

        assertTrue(actions(plan).contains(AgentAction.GET_DATETIME));
        assertEquals(2, client.requests().size());
        String repair = client.requests().getLast().instructions();
        assertTrue(repair.contains(
                "GOAL_COVERAGE_VALIDATOR:MISSING_REQUIRED_DATETIME_ACTION"));
        assertTrue(repair.contains("""
                REQUIRED_ACTIONS_FOR_THIS_GOAL:
                GET_DATETIME
                GET_WEATHER
                RUN_EXERCISE_SKILL
                RUN_MEAL_SKILL
                CLOSED_LOOP_REQUIREMENTS:
                """));
        assertTrue(repair.contains("EXACTLY_ONE_VALIDATE"));
        assertTrue(repair.contains("EXACTLY_ONE_FINAL_SYNTHESIZE"));
        assertTrue(repair.contains("VALIDATE_COVERS_ALL_BUSINESS_BRANCHES"));
        assertFalse(repair.contains("OTHER_PLAN_VALIDATION_ERROR"));
    }

    @Test
    void secondPlanWithValidateCoverageErrorIsNormalizedWithoutAnotherLlmCall() {
        FakeAgentPlanningClient client = new FakeAgentPlanningClient()
                .respondTo(
                        REAL_VALIDATION_GOAL,
                        missingDatetimePlanJson(REAL_VALIDATION_GOAL),
                        invalidValidateCoveragePlanJson(REAL_VALIDATION_GOAL)
                );
        Logger logger = (Logger) LoggerFactory.getLogger(LlmAgentPlanner.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        AgentPlan plan;
        try {
            plan = planner(client).plan(REAL_VALIDATION_GOAL);
        } finally {
            logger.detachAppender(appender);
        }

        assertEquals(2, client.requests().size());
        assertEquals(1, LlmAgentPlanner.MAX_REPAIR_ATTEMPTS);
        AgentStep validation = plan.steps().stream()
                .filter(step -> step.action() == AgentAction.VALIDATE)
                .findFirst().orElseThrow();
        assertEquals(5, validation.dependsOn().size());
        assertTrue(new AgentPlanValidator().validate(plan).valid());
        List<String> logs = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
        assertTrue(logs.stream().anyMatch(message ->
                message.contains("planner closure normalized")));
        assertTrue(logs.stream().noneMatch(message -> message.contains("OTHER_PLAN_VALIDATION_ERROR")));
        assertTrue(logs.stream().noneMatch(message -> message.contains(REAL_VALIDATION_GOAL)));
        assertTrue(logs.stream().noneMatch(message -> message.contains("\"steps\"")));
    }

    @Test
    void normalizesClosureOnlyErrorsWithoutCallingLlmRepair() {
        FakeAgentPlanningClient client = new FakeAgentPlanningClient()
                .respondTo(PROVIDER_OUTAGE_GOAL, closureInvalidPlanJson(PROVIDER_OUTAGE_GOAL));
        AgentRunMetricsCollector collector = new AgentRunMetricsCollector();

        AgentPlan plan = planner(client).plan(
                PROVIDER_OUTAGE_GOAL, AgentRunMetrics.observe(collector));

        assertEquals(1, client.requests().size());
        assertTrue(new AgentPlanValidator().validate(plan).valid());
        assertEquals(1, collector.snapshot().plannerClosureNormalizedCount());
        assertEquals(0, collector.snapshot().deterministicPlannerFallbackCount());
    }

    @Test
    void totalTransientProviderOutageUsesValidatedDeterministicPlan() {
        AgentPlanningClient unavailable = (goal, instructions) -> {
            throw new AgentProviderException(
                    "PLANNING", AgentProviderFailureCategory.TIMEOUT, null);
        };
        AgentRunMetricsCollector collector = new AgentRunMetricsCollector();

        AgentPlan plan = planner(unavailable).plan(
                PROVIDER_OUTAGE_GOAL, AgentRunMetrics.observe(collector));

        assertTrue(new AgentPlanValidator().validate(plan).valid());
        assertTrue(new GoalCoverageValidator().validate(PROVIDER_OUTAGE_GOAL, plan).valid());
        assertEquals(1, collector.snapshot().deterministicPlannerFallbackCount());
        assertEquals(AgentFallbackReason.TIMEOUT,
                collector.snapshot().deterministicPlannerFallbackReason());
    }

    @Test
    void invalidPlanAfterRepairUsesDeterministicLastResort() {
        FakeAgentPlanningClient client = new FakeAgentPlanningClient()
                .respondTo(PROVIDER_OUTAGE_GOAL, "bad-json", "still-bad-json");
        AgentRunMetricsCollector collector = new AgentRunMetricsCollector();

        AgentPlan plan = planner(client).plan(
                PROVIDER_OUTAGE_GOAL, AgentRunMetrics.observe(collector));

        assertEquals(2, client.requests().size());
        assertTrue(new AgentPlanValidator().validate(plan).valid());
        assertEquals(AgentFallbackReason.INVALID_PLAN_AFTER_REPAIR,
                collector.snapshot().deterministicPlannerFallbackReason());
    }

    @Test
    void authenticationFailureDoesNotUseDeterministicPlan() {
        AgentPlanningClient unauthorized = (goal, instructions) -> {
            throw new AgentProviderException(
                    "PLANNING", AgentProviderFailureCategory.NON_RETRYABLE, null);
        };
        AgentRunMetricsCollector collector = new AgentRunMetricsCollector();

        assertThrows(AgentPlanningException.class, () -> planner(unauthorized).plan(
                PROVIDER_OUTAGE_GOAL, AgentRunMetrics.observe(collector)));

        assertEquals(0, collector.snapshot().deterministicPlannerFallbackCount());
    }

    @Test
    void nonTemporalRepairSummaryDoesNotRequireDatetime() {
        String goal = "给我一个增肌饮食建议";
        FakeAgentPlanningClient client = new FakeAgentPlanningClient()
                .respondTo(goal, "not-json", nonTemporalMealPlanJson(goal));

        AgentPlan plan = planner(client).plan(goal);

        assertFalse(actions(plan).contains(AgentAction.GET_DATETIME));
        String repair = client.requests().getLast().instructions();
        String requiredActions = repair.substring(
                repair.indexOf("REQUIRED_ACTIONS_FOR_THIS_GOAL:"),
                repair.indexOf("CLOSED_LOOP_REQUIREMENTS:"));
        assertTrue(requiredActions.contains("RUN_MEAL_SKILL"));
        assertFalse(requiredActions.contains("GET_DATETIME"));
        assertFalse(requiredActions.contains("GET_WEATHER"));
        assertFalse(requiredActions.contains("RUN_EXERCISE_SKILL"));
    }

    @Test
    void planningHasNoToolSkillOrRagDependency() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        SkillRegistry skillRegistry = mock(SkillRegistry.class);
        RagRetriever ragRetriever = mock(RagRetriever.class);
        FakeAgentPlanningClient client = new FakeAgentPlanningClient()
                .respondTo(DAILY_GOAL, smallPlanJson(DAILY_GOAL));

        AgentPlan plan = planner(client).plan(DAILY_GOAL);

        assertEquals(5, plan.steps().size());
        verifyNoInteractions(toolRegistry, skillRegistry, ragRetriever);
    }

    @Test
    void instructionsDefineActionsJsonAndMedicalSafetyWithoutGoalSpecificTemplate() {
        String instructions = LlmAgentPlanner.INITIAL_INSTRUCTIONS;

        for (AgentAction action : AgentAction.values()) {
            assertTrue(instructions.contains(action.name()));
        }
        assertTrue(instructions.contains("3～12"));
        assertTrue(instructions.contains("只能返回一个 JSON object"));
        assertTrue(instructions.contains("不得规划疾病诊断"));
        assertTrue(instructions.contains("不得使用固定计划模板"));
        assertTrue(instructions.contains("每一步必须提供 inputs object"));
        assertTrue(instructions.contains("最多提供三日预报"));
        assertTrue(instructions.contains("不得声称取得真实 7 日天气"));
        assertTrue(instructions.contains("未来N天"));
        assertTrue(instructions.contains("不得凭模型内部时间知识"));
        assertTrue(instructions.contains("GET_DATETIME 应在依赖日期范围的业务步骤之前完成"));
        assertTrue(instructions.contains("canonical goal"));
    }

    @Test
    void rejectsBlankGoalWithoutCallingModel() {
        FakeAgentPlanningClient client = new FakeAgentPlanningClient();

        assertThrows(AgentPlanningException.class, () -> planner(client).plan(" "));
        assertTrue(client.requests().isEmpty());
    }

    private LlmAgentPlanner planner(AgentPlanningClient client) {
        return new LlmAgentPlanner(client, new ObjectMapper());
    }

    private List<AgentAction> actions(AgentPlan plan) {
        return plan.steps().stream().map(AgentStep::action).toList();
    }

    private String sevenDayPlanJson() {
        return """
                {
                  "goal": "制定未来7天大学生增肌健康生活方案",
                  "steps": [
                    {"id":"S1","action":"GET_DATETIME","description":"确定计划日期范围","reason":"建立七天计划时间基准","dependsOn":[],"inputs":{"timezone":"Asia/Shanghai"}},
                    {"id":"S2","action":"GET_WEATHER","description":"获取未来三天天气趋势","reason":"用近期真实天气安排前三天室内外运动","dependsOn":["S1"],"inputs":{"location":"镇江","period":"THREE_DAYS"}},
                    {"id":"S3","action":"RETRIEVE_KNOWLEDGE","description":"获取一般性增肌生活知识","reason":"形成安全的生活建议依据","dependsOn":[],"inputs":{"query":"大学生七天增肌饮食运动作息"}},
                    {"id":"S4","action":"RUN_EXERCISE_SKILL","description":"设计七天运动安排","reason":"前三天适配真实天气，后续采用一般安排","dependsOn":["S2","S3"],"inputs":{"request":"为健康大学生设计七天增肌运动安排"}},
                    {"id":"S5","action":"RUN_MEAL_SKILL","description":"设计七天饮食安排","reason":"支持一般性增肌营养目标","dependsOn":["S3"],"inputs":{"request":"为健康大学生设计七天增肌饮食安排"}},
                    {"id":"S6","action":"VALIDATE","description":"检查运动饮食与作息协调性","reason":"避免计划冲突和不合理负荷","dependsOn":["S4","S5"],"inputs":{}},
                    {"id":"S7","action":"SYNTHESIZE","description":"汇总七天健康生活方案并说明天气仅覆盖三天","reason":"输出统一可执行的最终计划","dependsOn":["S6"],"inputs":{}}
                  ]
                }
                """;
    }

    private String smallPlanJson(String goal) {
        return """
                {
                  "goal": "%s",
                  "steps": [
                    {"id":"S1","action":"GET_DATETIME","description":"确认今天的时间范围","reason":"安排规律作息","dependsOn":[],"inputs":{}},
                    {"id":"S2","action":"RETRIEVE_KNOWLEDGE","description":"获取轻量运动与作息知识","reason":"采用一般性健康建议","dependsOn":[],"inputs":{"query":"今日规律作息与轻量运动"}},
                    {"id":"S3","action":"RUN_EXERCISE_SKILL","description":"形成今日轻量运动安排","reason":"匹配今日作息目标","dependsOn":["S1","S2"],"inputs":{"request":"安排今天的轻量运动"}},
                    {"id":"S4","action":"VALIDATE","description":"检查今日计划信息完整性","reason":"确保真实结果完整且一致","dependsOn":["S3"],"inputs":{}},
                    {"id":"S5","action":"SYNTHESIZE","description":"汇总今日作息和运动计划","reason":"输出简洁的最终安排","dependsOn":["S4"],"inputs":{}}
                  ]
                }
                """.formatted(goal);
    }

    private String missingExercisePlanJson(String goal) {
        return """
                {
                  "goal": "%s",
                  "steps": [
                    {"id":"S1","action":"GET_DATETIME","description":"确定日期","reason":"建立计划时间范围","dependsOn":[],"inputs":{}},
                    {"id":"S2","action":"GET_WEATHER","description":"获取三日天气","reason":"调整近期户外安排","dependsOn":["S1"],"inputs":{"location":"镇江","period":"THREE_DAYS"}},
                    {"id":"S3","action":"RETRIEVE_KNOWLEDGE","description":"获取健康知识","reason":"支持一般作息安排","dependsOn":[],"inputs":{"query":"大学生健康作息"}},
                    {"id":"S4","action":"RUN_MEAL_SKILL","description":"生成饮食方案","reason":"满足饮食要求","dependsOn":["S3"],"inputs":{"request":"生成七天饮食方案"}},
                    {"id":"S5","action":"VALIDATE","description":"校验结果","reason":"确保执行闭环","dependsOn":["S2","S4"],"inputs":{}},
                    {"id":"S6","action":"SYNTHESIZE","description":"汇总方案","reason":"输出完整方案","dependsOn":["S5"],"inputs":{}}
                  ]
                }
                """.formatted(goal);
    }

    private String missingDatetimePlanJson() {
        return missingDatetimePlanJson(SEVEN_DAY_GOAL);
    }

    private String missingDatetimePlanJson(String goal) {
        return """
                {
                  "goal": "%s",
                  "steps": [
                    {"id":"S1","action":"GET_WEATHER","description":"获取未来三天天气趋势","reason":"安排前三天室内外运动","dependsOn":[],"inputs":{"location":"镇江","period":"THREE_DAYS"}},
                    {"id":"S2","action":"RETRIEVE_KNOWLEDGE","description":"获取增肌知识","reason":"形成安全建议依据","dependsOn":[],"inputs":{"query":"大学生七天增肌生活"}},
                    {"id":"S3","action":"RUN_EXERCISE_SKILL","description":"设计运动安排","reason":"满足运动目标","dependsOn":["S1","S2"],"inputs":{"request":"设计七天运动安排"}},
                    {"id":"S4","action":"RUN_MEAL_SKILL","description":"设计饮食安排","reason":"满足增肌饮食目标","dependsOn":["S2"],"inputs":{"request":"设计七天饮食安排"}},
                    {"id":"S5","action":"VALIDATE","description":"校验结果","reason":"确保执行闭环","dependsOn":["S3","S4"],"inputs":{}},
                    {"id":"S6","action":"SYNTHESIZE","description":"汇总方案","reason":"输出完整方案","dependsOn":["S5"],"inputs":{}}
                  ]
                }
                """.formatted(goal);
    }

    private String invalidValidateCoveragePlanJson(String goal) {
        return """
                {
                  "goal": "%s",
                  "steps": [
                    {"id":"S1","action":"GET_DATETIME","description":"确定日期","reason":"建立计划日期范围","dependsOn":[],"inputs":{"timezone":"Asia/Shanghai"}},
                    {"id":"S2","action":"GET_WEATHER","description":"获取三日天气","reason":"安排近期户外活动","dependsOn":["S1"],"inputs":{"location":"镇江","period":"THREE_DAYS"}},
                    {"id":"S3","action":"RETRIEVE_KNOWLEDGE","description":"获取健康知识","reason":"支持一般健康安排","dependsOn":[],"inputs":{"query":"大学生健康生活"}},
                    {"id":"S4","action":"RUN_EXERCISE_SKILL","description":"设计运动计划","reason":"满足运动要求","dependsOn":["S2","S3"],"inputs":{"request":"设计七天运动计划"}},
                    {"id":"S5","action":"RUN_MEAL_SKILL","description":"设计饮食计划","reason":"满足饮食要求","dependsOn":["S3"],"inputs":{"request":"设计七天饮食计划"}},
                    {"id":"S6","action":"VALIDATE","description":"校验计划","reason":"检查执行结果","dependsOn":["S4"],"inputs":{}},
                    {"id":"S7","action":"SYNTHESIZE","description":"汇总计划","reason":"输出完整方案","dependsOn":["S6"],"inputs":{}}
                  ]
                }
                """.formatted(goal);
    }

    private String closureInvalidPlanJson(String goal) {
        return """
                {
                  "goal": "%s",
                  "steps": [
                    {"id":"S1","action":"GET_DATETIME","description":"确定日期","reason":"建立日期范围","dependsOn":[],"inputs":{}},
                    {"id":"S2","action":"GET_WEATHER","description":"获取天气","reason":"安排室内外活动","dependsOn":["S1"],"inputs":{"location":"镇江市","period":"THREE_DAYS"}},
                    {"id":"S3","action":"RUN_EXERCISE_SKILL","description":"运动建议","reason":"满足运动目标","dependsOn":["S2"],"inputs":{}},
                    {"id":"S4","action":"RUN_MEAL_SKILL","description":"饮食建议","reason":"满足饮食目标","dependsOn":["S1"],"inputs":{}},
                    {"id":"S6","action":"SYNTHESIZE","description":"汇总","reason":"输出结果","dependsOn":["S3"],"inputs":{}},
                    {"id":"S5","action":"VALIDATE","description":"校验","reason":"执行闭环","dependsOn":["S2"],"inputs":{}}
                  ]
                }
                """.formatted(goal.replace("\n", "\\n"));
    }

    private String nonTemporalMealPlanJson(String goal) {
        return """
                {
                  "goal": "%s",
                  "steps": [
                    {"id":"S1","action":"RETRIEVE_KNOWLEDGE","description":"检索增肌饮食知识","reason":"提供一般营养依据","dependsOn":[],"inputs":{"query":"大学生增肌饮食"}},
                    {"id":"S2","action":"RUN_MEAL_SKILL","description":"生成饮食建议","reason":"满足饮食目标","dependsOn":["S1"],"inputs":{"request":"生成增肌饮食建议"}},
                    {"id":"S3","action":"CALCULATE","description":"计算示例餐次","reason":"补充可执行建议","dependsOn":[],"inputs":{"expression":"3 + 1"}},
                    {"id":"S4","action":"VALIDATE","description":"校验结果","reason":"确保执行闭环","dependsOn":["S2","S3"],"inputs":{}},
                    {"id":"S5","action":"SYNTHESIZE","description":"汇总建议","reason":"输出完整建议","dependsOn":["S4"],"inputs":{}}
                  ]
                }
                """.formatted(goal);
    }

    private static final class FakeAgentPlanningClient implements AgentPlanningClient {
        private final Map<String, Queue<String>> responses = new HashMap<>();
        private final List<Request> requests = new ArrayList<>();

        private FakeAgentPlanningClient respondTo(String goal, String... rawPlans) {
            responses.computeIfAbsent(goal, ignored -> new ArrayDeque<>()).addAll(List.of(rawPlans));
            return this;
        }

        @Override
        public String generatePlan(String goal, String instructions) {
            requests.add(new Request(goal, instructions));
            Queue<String> available = responses.get(goal);
            if (available == null || available.isEmpty()) {
                throw new IllegalStateException("No fake response configured");
            }
            return available.remove();
        }

        private List<Request> requests() {
            return List.copyOf(requests);
        }

        private List<String> requestedGoals() {
            return requests.stream().map(Request::goal).toList();
        }
    }

    private record Request(String goal, String instructions) {
    }
}
