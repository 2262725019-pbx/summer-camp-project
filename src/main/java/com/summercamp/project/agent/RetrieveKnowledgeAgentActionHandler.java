package com.summercamp.project.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summercamp.project.rag.RagContext;
import com.summercamp.project.rag.RagRetriever;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public final class RetrieveKnowledgeAgentActionHandler implements AgentActionHandler {
    private final RagRetriever ragRetriever;
    private final ObjectMapper objectMapper;
    private final AgentActionInputValidator inputValidator = new AgentActionInputValidator();

    public RetrieveKnowledgeAgentActionHandler(RagRetriever ragRetriever, ObjectMapper objectMapper) {
        this.ragRetriever = Objects.requireNonNull(ragRetriever, "ragRetriever must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public AgentAction action() {
        return AgentAction.RETRIEVE_KNOWLEDGE;
    }

    @Override
    public AgentObservation execute(AgentStep step, AgentExecutionContext context) {
        if (step.action() != AgentAction.RETRIEVE_KNOWLEDGE) {
            return new AgentObservation(
                    step.id(),
                    false,
                    "Handler action does not match step action",
                    Map.of("code", "INVALID_INPUT")
            );
        }
        List<String> errors = inputValidator.validate(step);
        if (!errors.isEmpty()) {
            return new AgentObservation(
                    step.id(),
                    false,
                    String.join("; ", errors),
                    Map.of("code", "INVALID_INPUT")
            );
        }

        context.metrics().recordRagQuery();
        RagContext ragContext = ragRetriever.retrieve(step.inputs().get("query"));
        if (ragContext == null || !ragContext.matched()) {
            return new AgentObservation(
                    step.id(),
                    true,
                    "未检索到匹配的本地知识",
                    Map.of("matched", "false", "documentIds", "[]", "promptContext", "")
            );
        }
        return new AgentObservation(
                step.id(),
                true,
                "已检索到 " + ragContext.hits().size() + " 条本地知识",
                Map.of(
                        "matched", "true",
                        "documentIds", serializeDocumentIds(ragContext.documentIds()),
                        "promptContext", ragContext.promptContext()
                )
        );
    }

    private String serializeDocumentIds(List<String> documentIds) {
        try {
            return objectMapper.writeValueAsString(documentIds);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize RAG document ids", exception);
        }
    }
}
