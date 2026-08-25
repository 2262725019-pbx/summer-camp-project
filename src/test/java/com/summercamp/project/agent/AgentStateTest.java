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
        AgentStep weather = new AgentStep(
                "weather",
                AgentAction.GET_WEATHER,
                "读取天气",
                "安排锻炼",
                dependencies
        );
        dependencies.add("external-change");
        assertEquals(List.of("datetime"), weather.dependsOn());
        assertThrows(UnsupportedOperationException.class, () -> weather.dependsOn().add("change"));

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
