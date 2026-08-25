package com.summercamp.project.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
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

class LlmAgentPlannerTest {
    private static final String SEVEN_DAY_GOAL = "制定未来7天大学生增肌健康生活方案";
    private static final String DAILY_GOAL = "帮我安排今天的规律作息和轻量运动";

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
        assertTrue(client.requests().getLast().instructions().contains("not valid JSON"));
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
        assertTrue(client.requests().getLast().instructions().contains("supported AgentAction"));
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
        assertTrue(client.requests().getLast().instructions().contains("cycle"));
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
        assertTrue(client.requests().getLast().instructions().contains("SYNTHESIZE"));
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
    void repairsPlanThatChangesTheRequestedGoal() {
        String changedGoal = smallPlanJson(DAILY_GOAL).replace(DAILY_GOAL, "另一个目标");
        FakeAgentPlanningClient client = new FakeAgentPlanningClient()
                .respondTo(DAILY_GOAL, changedGoal, smallPlanJson(DAILY_GOAL));

        AgentPlan plan = planner(client).plan(DAILY_GOAL);

        assertEquals(DAILY_GOAL, plan.goal());
        assertTrue(client.requests().getLast().instructions().contains("exactly match"));
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
