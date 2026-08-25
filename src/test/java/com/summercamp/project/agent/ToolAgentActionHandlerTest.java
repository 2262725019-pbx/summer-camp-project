package com.summercamp.project.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summercamp.project.llm.ChatMessage;
import com.summercamp.project.tool.ToolContext;
import com.summercamp.project.tool.ToolRegistry;
import com.summercamp.project.tool.ToolResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ToolAgentActionHandlerTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

    @Test
    void dateTimeUsesCurrentDateTimeTool() {
        when(toolRegistry.invoke(anyString(), anyString(), any())).thenReturn(successInvocation());
        AgentStep step = step(AgentAction.GET_DATETIME, Map.of("timezone", "Asia/Shanghai"));

        AgentObservation observation = new GetDateTimeAgentActionHandler(toolRegistry, objectMapper)
                .execute(step, context(step));

        assertTrue(observation.success());
        verifyInvocation("get_current_datetime", Map.of("timezone", "Asia/Shanghai"));
    }

    @Test
    void weatherUsesExactJsonAndPropagatesToolContext() {
        when(toolRegistry.invoke(anyString(), anyString(), any())).thenReturn(successInvocation());
        AgentStep step = step(AgentAction.GET_WEATHER, Map.of(
                "location", "镇江",
                "period", "THREE_DAYS"
        ));
        List<ChatMessage> history = List.of(
                ChatMessage.user("我想制定健康计划"),
                ChatMessage.assistant("请提供目标")
        );
        AgentExecutionContext context = context(
                "student-7",
                "制定未来七天健康生活计划",
                history,
                false,
                step
        );

        AgentObservation observation = new GetWeatherAgentActionHandler(toolRegistry, objectMapper)
                .execute(step, context);

        assertTrue(observation.success());
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<ToolContext> contextCaptor = ArgumentCaptor.forClass(ToolContext.class);
        verify(toolRegistry).invoke(eq("get_weather"), jsonCaptor.capture(), contextCaptor.capture());
        assertEquals(
                objectMapper.valueToTree(Map.of("location", "镇江", "period", "THREE_DAYS")),
                readTree(jsonCaptor.getValue())
        );
        assertEquals("student-7", contextCaptor.getValue().userId());
        assertEquals("制定未来七天健康生活计划", contextCaptor.getValue().userText());
        assertEquals(history, contextCaptor.getValue().history());
    }

    @Test
    void calculateUsesExpressionOnly() {
        when(toolRegistry.invoke(anyString(), anyString(), any())).thenReturn(successInvocation());
        AgentStep step = step(AgentAction.CALCULATE, Map.of("expression", "(12 + 8) / 2"));

        AgentObservation observation = new CalculateAgentActionHandler(toolRegistry, objectMapper)
                .execute(step, context(step));

        assertTrue(observation.success());
        verifyInvocation("calculate", Map.of("expression", "(12 + 8) / 2"));
    }

    @Test
    void createTodoUsesItem() {
        when(toolRegistry.invoke(anyString(), anyString(), any())).thenReturn(successInvocation());
        AgentStep step = step(AgentAction.CREATE_TODO, Map.of("item", "今晚 23 点前睡觉"));

        AgentObservation observation = new CreateTodoAgentActionHandler(toolRegistry, objectMapper)
                .execute(step, context(step));

        assertTrue(observation.success());
        verifyInvocation("add_todo", Map.of("item", "今晚 23 点前睡觉"));
    }

    @Test
    void toolFailureBecomesFailureObservation() {
        when(toolRegistry.invoke(anyString(), anyString(), any())).thenReturn(new ToolRegistry.Invocation(
                false,
                ToolResult.text("safe failure"),
                "{\"success\":false,\"error\":\"safe failure\"}"
        ));
        AgentStep step = step(AgentAction.GET_DATETIME, Map.of());

        AgentObservation observation = new GetDateTimeAgentActionHandler(toolRegistry, objectMapper)
                .execute(step, context(step));

        assertFalse(observation.success());
        assertEquals("TOOL_EXECUTION_FAILED", observation.structuredData().get("code"));
        assertTrue(observation.summary().contains("safe failure"));
    }

    @Test
    void invalidWeatherInputFailsWithoutCallingToolRegistry() {
        AgentStep step = step(AgentAction.GET_WEATHER, Map.of("period", "THREE_DAYS"));
        AgentPlan plan = new AgentPlan("original goal", List.of(step));
        AgentExecutor executor = new AgentExecutor(new AgentActionHandlerRegistry(List.of(
                new GetWeatherAgentActionHandler(toolRegistry, objectMapper)
        )));

        AgentState state = executor.execute(plan);
        AgentObservation observation = state.findObservation("S1").orElseThrow();

        assertFalse(observation.success());
        assertEquals(AgentStepStatus.FAILED, state.statusOf("S1"));
        assertEquals("INVALID_INPUT", observation.structuredData().get("code"));
        assertTrue(observation.summary().contains("location"));
        verifyNoInteractions(toolRegistry);
    }

    private void verifyInvocation(String expectedTool, Map<String, String> expectedArguments) {
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(toolRegistry).invoke(eq(expectedTool), jsonCaptor.capture(), any(ToolContext.class));
        assertEquals(objectMapper.valueToTree(expectedArguments), readTree(jsonCaptor.getValue()));
    }

    private ToolRegistry.Invocation successInvocation() {
        return new ToolRegistry.Invocation(
                true,
                ToolResult.text("ok"),
                "{\"success\":true,\"result\":\"ok\"}"
        );
    }

    private AgentStep step(AgentAction action, Map<String, String> inputs) {
        return new AgentStep("S1", action, "execute", "test", List.of(), inputs);
    }

    private AgentExecutionContext context(AgentStep step) {
        return context("user", "original goal", List.of(), false, step);
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

    private com.fasterxml.jackson.databind.JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new AssertionError(exception);
        }
    }
}
