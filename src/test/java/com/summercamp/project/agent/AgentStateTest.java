package com.summercamp.project.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentStateTest {
    @Test
    void recordsAndQueriesObservations() {
        AgentPlan plan = plan();
        AgentState state = new AgentState(plan);
        AgentObservation completed = new AgentObservation(
                "datetime",
                true,
                "当前时间为 08:00",
                Map.of("localTime", "08:00")
        );
        AgentObservation failed = new AgentObservation("weather", false, "天气服务暂不可用");

        assertTrue(state.statuses().values().stream()
                .allMatch(status -> status == AgentStepStatus.PENDING));

        state.recordObservation(completed);
        state.recordObservation(failed);

        assertEquals(plan.goal(), state.goal());
        assertEquals(plan, state.plan());
        assertEquals(completed, state.findObservation("datetime").orElseThrow());
        assertTrue(state.findObservation("missing").isEmpty());
        assertTrue(state.isStepCompleted("datetime"));
        assertFalse(state.isStepCompleted("weather"));
        assertEquals(List.of(plan.steps().getFirst()), state.completedSteps());
        assertEquals(List.of(completed, failed), state.observations());
    }

    @Test
    void rejectsObservationForStepOutsidePlan() {
        AgentState state = new AgentState(plan());

        assertThrows(
                IllegalArgumentException.class,
                () -> state.recordObservation(new AgentObservation("unknown", true, "unexpected"))
        );
    }

    @Test
    void terminalObservationCannotBeOverwritten() {
        AgentState state = new AgentState(plan());
        AgentObservation first = new AgentObservation("datetime", true, "first result");
        state.recordObservation(first);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> state.recordObservation(new AgentObservation("datetime", false, "replacement"))
        );

        assertEquals(first, state.findObservation("datetime").orElseThrow());
        assertEquals(AgentStepStatus.COMPLETED, state.statusOf("datetime"));
        assertTrue(exception.getMessage().contains("already recorded"));
    }

    @Test
    void collectionsCannotBeModifiedExternally() {
        List<String> dependencies = new ArrayList<>(List.of("datetime"));
        Map<String, String> inputs = new LinkedHashMap<>(Map.of("location", "镇江"));
        AgentStep weather = new AgentStep(
                "weather",
                AgentAction.GET_WEATHER,
                "读取天气",
                "安排锻炼",
                dependencies,
                inputs
        );
        dependencies.add("external-change");
        inputs.put("period", "THREE_DAYS");
        assertEquals(List.of("datetime"), weather.dependsOn());
        assertEquals(Map.of("location", "镇江"), weather.inputs());
        assertThrows(UnsupportedOperationException.class, () -> weather.dependsOn().add("change"));
        assertThrows(UnsupportedOperationException.class, () -> weather.inputs().put("period", "TODAY"));
        assertThrows(NullPointerException.class, () -> new AgentStep(
                "bad",
                AgentAction.GET_WEATHER,
                "读取天气",
                "测试",
                List.of(),
                java.util.Collections.singletonMap("location", null)
        ));
        assertThrows(NullPointerException.class, () -> new AgentStep(
                "bad",
                AgentAction.GET_WEATHER,
                "读取天气",
                "测试",
                List.of(),
                java.util.Collections.singletonMap(null, "镇江")
        ));
        assertThrows(NullPointerException.class, () -> new AgentStep(
                "bad",
                AgentAction.GET_WEATHER,
                "读取天气",
                "测试",
                List.of(),
                (Map<String, String>) null
        ));

        List<AgentStep> steps = new ArrayList<>(List.of(
                step("datetime", AgentAction.GET_DATETIME),
                weather,
                step("knowledge", AgentAction.RETRIEVE_KNOWLEDGE),
                new AgentStep("final", AgentAction.SYNTHESIZE, "汇总", "输出结果", List.of("knowledge"))
        ));
        AgentPlan agentPlan = new AgentPlan("健康计划", steps);
        steps.clear();
        assertEquals(4, agentPlan.steps().size());
        assertThrows(UnsupportedOperationException.class, () -> agentPlan.steps().clear());

        Map<String, String> structuredData = new LinkedHashMap<>();
        structuredData.put("temperature", "28");
        AgentObservation observation = new AgentObservation("weather", true, "晴", structuredData);
        structuredData.put("binary", "external-change");
        assertEquals(Map.of("temperature", "28"), observation.structuredData());
        assertThrows(
                UnsupportedOperationException.class,
                () -> observation.structuredData().put("humidity", "60")
        );

        AgentState state = new AgentState(agentPlan);
        state.recordObservation(observation);
        assertThrows(UnsupportedOperationException.class, () -> state.observations().clear());
        assertThrows(UnsupportedOperationException.class, () -> state.completedSteps().clear());

        AgentPlanValidationResult validationResult = new AgentPlanValidator().validate(agentPlan);
        assertThrows(UnsupportedOperationException.class, () -> validationResult.errors().add("change"));
    }

    @Test
    void restoresCompletedStepsAndResetsOnlyWaitingBranchForResume() {
        AgentPlan plan = new AgentPlan("健康规划", List.of(
                step("datetime", AgentAction.GET_DATETIME),
                new AgentStep("meal", AgentAction.RUN_MEAL_SKILL,
                        "生成饮食", "补齐资料", List.of("datetime")),
                new AgentStep("validate", AgentAction.VALIDATE,
                        "校验", "闭环", List.of("meal")),
                new AgentStep("synthesis", AgentAction.SYNTHESIZE,
                        "汇总", "输出", List.of("validate"))));
        AgentState state = new AgentState(plan);
        AgentObservation completed = new AgentObservation("datetime", true, "日期完成");
        state.recordObservation(completed);
        state.recordObservation(waiting("meal"));
        state.markSkipped("validate", "waiting dependency");
        state.markSkipped("synthesis", "waiting dependency");

        AgentState restored = AgentState.restoreForResume(
                AgentStateSnapshot.from(state), "meal");

        assertEquals(AgentStepStatus.COMPLETED, restored.statusOf("datetime"));
        assertEquals(completed, restored.findObservation("datetime").orElseThrow());
        assertEquals(AgentStepStatus.PENDING, restored.statusOf("meal"));
        assertEquals(AgentStepStatus.PENDING, restored.statusOf("validate"));
        assertEquals(AgentStepStatus.PENDING, restored.statusOf("synthesis"));
        assertTrue(restored.findObservation("meal").isEmpty());
        assertTrue(restored.findObservation("validate").isEmpty());
        assertTrue(restored.findObservation("synthesis").isEmpty());
        assertThrows(IllegalStateException.class, () -> restored.recordObservation(
                new AgentObservation("datetime", false, "must not overwrite")));
    }

    @Test
    void rejectsRunningCheckpointState() {
        AgentPlan plan = new AgentPlan("健康规划", List.of(
                step("datetime", AgentAction.GET_DATETIME),
                step("meal", AgentAction.RUN_MEAL_SKILL)));
        AgentState state = new AgentState(plan);
        state.recordObservation(new AgentObservation("datetime", true, "完成"));
        state.markRunning("meal");

        assertThrows(IllegalArgumentException.class, () -> AgentState.restoreForResume(
                AgentStateSnapshot.from(state), "meal"));
    }

    @Test
    void preservesUnrelatedNonRecoverableFailureAndSkippedBranch() {
        AgentPlan plan = new AgentPlan("健康规划", List.of(
                step("provider", AgentAction.RETRIEVE_KNOWLEDGE),
                new AgentStep("provider-dependent", AgentAction.CREATE_TODO,
                        "创建待办", "失败分支", List.of("provider")),
                step("meal", AgentAction.RUN_MEAL_SKILL),
                new AgentStep("meal-dependent", AgentAction.VALIDATE,
                        "校验", "等待分支", List.of("meal"))));
        AgentState state = new AgentState(plan);
        AgentObservation providerFailure = new AgentObservation(
                "provider", false, "provider timeout", Map.of("code", "HANDLER_FAILURE"));
        state.recordObservation(providerFailure);
        state.markSkipped("provider-dependent", "provider failed");
        state.recordObservation(waiting("meal"));
        state.markSkipped("meal-dependent", "waiting dependency");

        AgentState restored = AgentState.restoreForResume(
                AgentStateSnapshot.from(state), "meal");

        assertEquals(AgentStepStatus.FAILED, restored.statusOf("provider"));
        assertEquals(providerFailure, restored.findObservation("provider").orElseThrow());
        assertEquals(AgentStepStatus.SKIPPED, restored.statusOf("provider-dependent"));
        assertEquals(AgentStepStatus.PENDING, restored.statusOf("meal"));
        assertEquals(AgentStepStatus.PENDING, restored.statusOf("meal-dependent"));
    }

    private AgentObservation waiting(String stepId) {
        return new AgentObservation(
                stepId,
                false,
                "请补资料",
                Map.of("code", "NEEDS_USER_INPUT", "recoverable", "true"));
    }

    private AgentPlan plan() {
        return new AgentPlan("制定健康生活规划", List.of(
                step("datetime", AgentAction.GET_DATETIME),
                step("weather", AgentAction.GET_WEATHER),
                step("knowledge", AgentAction.RETRIEVE_KNOWLEDGE),
                new AgentStep("final", AgentAction.SYNTHESIZE, "汇总", "输出结果", List.of("knowledge"))
        ));
    }

    private AgentStep step(String id, AgentAction action) {
        return new AgentStep(id, action, "执行 " + id, "构建健康计划", List.of());
    }
}
