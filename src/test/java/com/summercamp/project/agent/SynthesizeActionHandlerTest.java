package com.summercamp.project.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.summercamp.project.rag.RagRetriever;
import com.summercamp.project.skill.SkillRegistry;
import com.summercamp.project.tool.ToolRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SynthesizeActionHandlerTest {
    @Test
    void synthesizesOnlyAfterSuccessfulValidationWithoutCapabilityRegistries() {
        AgentPlan plan = plan();
        AgentState state = validatedState(plan);
        AtomicInteger clientCalls = new AtomicInteger();
        AgentSynthesisClient client = (goal, context) -> {
            clientCalls.incrementAndGet();
            assertTrue(context.contains("真实天气"));
            return "这是基于真实结果生成的健康计划。";
        };
        ToolRegistry tools = mock(ToolRegistry.class);
        SkillRegistry skills = mock(SkillRegistry.class);
        RagRetriever rag = mock(RagRetriever.class);

        AgentObservation result = new SynthesizeActionHandler(
                new AgentSynthesisContextBuilder(), client).execute(
                plan.steps().getLast(), new AgentExecutionContext(plan.goal(), state, plan));

        assertTrue(result.success());
        assertEquals(SynthesizeActionHandler.SYNTHESIS_COMPLETED, result.structuredData().get("code"));
        assertEquals(1, clientCalls.get());
        verifyNoInteractions(tools, skills, rag);
    }

    @Test
    void blocksClientWhenValidationIsMissingOrFailed() {
        AgentPlan plan = plan();
        AgentState state = new AgentState(plan);
        state.recordObservation(new AgentObservation("datetime", true, "日期"));
        state.recordObservation(new AgentObservation("weather", true, "真实天气"));
        state.recordObservation(new AgentObservation("rag", true, "未匹配", Map.of("matched", "false")));
        state.recordObservation(new AgentObservation(
                "validate", false, "校验失败", Map.of("code", ValidateActionHandler.VALIDATION_FAILED)));
        AtomicInteger calls = new AtomicInteger();

        AgentObservation result = new SynthesizeActionHandler(
                new AgentSynthesisContextBuilder(), (goal, context) -> {
                    calls.incrementAndGet();
                    return "不应调用";
                }).execute(plan.steps().getLast(), new AgentExecutionContext(plan.goal(), state, plan));

        assertFalse(result.success());
        assertEquals(0, calls.get());
    }

    @Test
    void convertsBlankOrClientFailureToSafeFailure() {
        AgentPlan plan = plan();
        AgentState state = validatedState(plan);

        AgentObservation blank = new SynthesizeActionHandler(
                new AgentSynthesisContextBuilder(), (goal, context) -> " ").execute(
                plan.steps().getLast(), new AgentExecutionContext(plan.goal(), state, plan));
        AgentObservation failed = new SynthesizeActionHandler(
                new AgentSynthesisContextBuilder(), (goal, context) -> {
                    throw new IllegalStateException("provider stack and key");
                }).execute(plan.steps().getLast(), new AgentExecutionContext(plan.goal(), state, plan));

        assertFalse(blank.success());
        assertFalse(failed.success());
        assertFalse(failed.summary().contains("provider"));
    }

    private AgentState validatedState(AgentPlan plan) {
        AgentState state = new AgentState(plan);
        state.recordObservation(new AgentObservation("datetime", true, "日期"));
        state.recordObservation(new AgentObservation("weather", true, "真实天气"));
        state.recordObservation(new AgentObservation("rag", true, "未匹配", Map.of("matched", "false")));
        state.recordObservation(new AgentObservation(
                "validate", true, "校验通过", Map.of("code", ValidateActionHandler.VALIDATION_PASSED)));
        return state;
    }

    private AgentPlan plan() {
        return new AgentPlan("制定三日健康生活计划", List.of(
                step("datetime", AgentAction.GET_DATETIME, List.of()),
                step("weather", AgentAction.GET_WEATHER, List.of("datetime")),
                step("rag", AgentAction.RETRIEVE_KNOWLEDGE, List.of()),
                step("validate", AgentAction.VALIDATE, List.of("weather", "rag")),
                step("synthesis", AgentAction.SYNTHESIZE, List.of("validate"))
        ));
    }

    private AgentStep step(String id, AgentAction action, List<String> dependencies) {
        return new AgentStep(id, action, "执行", "原因", dependencies);
    }
}
