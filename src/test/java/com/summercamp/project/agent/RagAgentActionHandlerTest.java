package com.summercamp.project.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summercamp.project.rag.RagContext;
import com.summercamp.project.rag.RagDocument;
import com.summercamp.project.rag.RagRetriever;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RagAgentActionHandlerTest {
    private final RagRetriever ragRetriever = mock(RagRetriever.class);
    private final RetrieveKnowledgeAgentActionHandler handler =
            new RetrieveKnowledgeAgentActionHandler(ragRetriever, new ObjectMapper());

    @Test
    void hitPreservesDocumentIdsAndPromptContext() {
        RagDocument first = new RagDocument("sleep-1", "作息", List.of("睡眠"), "保持规律睡眠");
        RagDocument second = new RagDocument("exercise-2", "运动", List.of("运动"), "循序渐进");
        when(ragRetriever.retrieve("大学生作息运动"))
                .thenReturn(new RagContext(
                        List.of(new RagContext.Hit(first, 9), new RagContext.Hit(second, 8)),
                        "[sleep-1] 保持规律睡眠\n[exercise-2] 循序渐进"
                ));
        AgentStep step = step("R1", AgentAction.RETRIEVE_KNOWLEDGE,
                Map.of("query", "大学生作息运动"));

        AgentObservation observation = handler.execute(step, context(step));

        assertTrue(observation.success());
        assertEquals("true", observation.structuredData().get("matched"));
        assertEquals("[\"sleep-1\",\"exercise-2\"]", observation.structuredData().get("documentIds"));
        assertTrue(observation.structuredData().get("promptContext").contains("循序渐进"));
        verify(ragRetriever).retrieve("大学生作息运动");
    }

    @Test
    void missIsSuccessfulAndDoesNotBlockDependentStep() {
        when(ragRetriever.retrieve("不存在的知识")).thenReturn(RagContext.empty());
        List<String> order = new ArrayList<>();
        AgentStep ragStep = step("R1", AgentAction.RETRIEVE_KNOWLEDGE,
                Map.of("query", "不存在的知识"));
        AgentStep synthesis = new AgentStep(
                "S2",
                AgentAction.SYNTHESIZE,
                "汇总",
                "继续执行",
                List.of("R1"),
                Map.of()
        );
        AgentPlan plan = new AgentPlan("健康计划", List.of(ragStep, synthesis));
        FakeAgentActionHandler fakeSynthesis = FakeAgentActionHandler.succeeding(
                AgentAction.SYNTHESIZE,
                order
        );

        AgentState state = new AgentExecutor(new AgentActionHandlerRegistry(List.of(handler, fakeSynthesis)))
                .execute(plan);

        AgentObservation observation = state.findObservation("R1").orElseThrow();
        assertTrue(observation.success());
        assertEquals("false", observation.structuredData().get("matched"));
        assertEquals("[]", observation.structuredData().get("documentIds"));
        assertEquals(List.of("S2"), order);
        assertEquals(AgentStepStatus.COMPLETED, state.statusOf("S2"));
    }

    private AgentStep step(String id, AgentAction action, Map<String, String> inputs) {
        return new AgentStep(id, action, "execute", "test", List.of(), inputs);
    }

    private AgentExecutionContext context(AgentStep step) {
        AgentPlan plan = new AgentPlan("健康计划", List.of(step));
        return new AgentExecutionContext("健康计划", new AgentState(plan), plan);
    }
}
