package com.summercamp.project.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentSynthesisContextBuilderTest {
    private final AgentSynthesisContextBuilder builder = new AgentSynthesisContextBuilder();

    @Test
    void preservesPlanOrderAndExcludesFailedObservations() {
        AgentPlan plan = plan();
        AgentState state = new AgentState(plan);
        state.recordObservation(new AgentObservation("datetime", true, "先记录日期"));
        state.recordObservation(new AgentObservation("weather", false, "不可信天气 99 度"));
        state.recordObservation(new AgentObservation("rag", true, "后记录知识", Map.of("matched", "false")));

        String context = builder.build(plan.goal(), plan, state);

        assertTrue(context.indexOf("先记录日期") < context.indexOf("后记录知识"));
        assertFalse(context.contains("不可信天气"));
        assertTrue(context.contains("matched：false"));
        assertFalse(context.contains("datetime"));
    }

    @Test
    void enforcesPerObservationAndTotalLengthAndRedactsUnsafeData() {
        AgentPlan plan = plan();
        AgentState state = new AgentState(plan);
        String longValue = "内容".repeat(3_000);
        state.recordObservation(new AgentObservation(
                "datetime",
                true,
                longValue,
                Map.of("apiKey", "super-secret", "imageBase64", "data:image/png;base64,AAAA")));
        state.recordObservation(new AgentObservation("weather", true, longValue));
        state.recordObservation(new AgentObservation("rag", true, longValue));

        String context = builder.build(plan.goal(), plan, state);

        assertTrue(context.length() <= AgentSynthesisContextBuilder.MAX_TOTAL_CHARS);
        assertFalse(context.contains("super-secret"));
        assertFalse(context.contains("AAAA"));
    }

    private AgentPlan plan() {
        return new AgentPlan("健康目标", List.of(
                step("datetime", AgentAction.GET_DATETIME),
                step("weather", AgentAction.GET_WEATHER),
                step("rag", AgentAction.RETRIEVE_KNOWLEDGE),
                step("validate", AgentAction.VALIDATE),
                step("synthesis", AgentAction.SYNTHESIZE)
        ));
    }

    private AgentStep step(String id, AgentAction action) {
        return new AgentStep(id, action, "执行", "原因", List.of());
    }
}
