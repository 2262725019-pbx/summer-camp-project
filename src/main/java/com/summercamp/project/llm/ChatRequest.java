package com.summercamp.project.llm;

import java.util.List;
import java.util.Set;

public record ChatRequest(
        List<ChatMessage> history,
        String text,
        List<ImageInput> images,
        String groundingContext,
        Set<String> disabledTools,
        ChatProviderPolicy providerPolicy) {

    public ChatRequest {
        history = List.copyOf(history);
        images = List.copyOf(images);
        text = text == null ? "" : text;
        groundingContext = groundingContext == null ? "" : groundingContext;
        disabledTools = disabledTools == null ? Set.of() : Set.copyOf(disabledTools);
        providerPolicy = providerPolicy == null ? ChatProviderPolicy.STANDARD : providerPolicy;
    }

    public ChatRequest(
            List<ChatMessage> history,
            String text,
            List<ImageInput> images,
            String groundingContext,
            Set<String> disabledTools
    ) {
        this(
                history,
                text,
                images,
                groundingContext,
                disabledTools,
                ChatProviderPolicy.STANDARD);
    }

    public ChatRequest(
            List<ChatMessage> history,
            String text,
            List<ImageInput> images,
            String groundingContext
    ) {
        this(
                history,
                text,
                images,
                groundingContext,
                Set.of(),
                ChatProviderPolicy.STANDARD);
    }

    public ChatRequest(List<ChatMessage> history, String text, List<ImageInput> images) {
        this(history, text, images, "", Set.of(), ChatProviderPolicy.STANDARD);
    }
}
