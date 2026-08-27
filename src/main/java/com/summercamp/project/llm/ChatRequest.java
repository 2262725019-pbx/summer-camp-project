package com.summercamp.project.llm;

import com.summercamp.project.tool.ToolAccessPolicy;
import java.util.List;

public record ChatRequest(
        List<ChatMessage> history,
        String text,
        List<ImageInput> images,
        String groundingContext,
        ToolAccessPolicy toolAccessPolicy,
        ChatProviderPolicy providerPolicy) {

    public ChatRequest {
        history = List.copyOf(history);
        images = List.copyOf(images);
        text = text == null ? "" : text;
        groundingContext = groundingContext == null ? "" : groundingContext;
        toolAccessPolicy = toolAccessPolicy == null
                ? ToolAccessPolicy.unrestricted()
                : toolAccessPolicy;
        providerPolicy = providerPolicy == null ? ChatProviderPolicy.STANDARD : providerPolicy;
    }

    public ChatRequest(
            List<ChatMessage> history,
            String text,
            List<ImageInput> images,
            String groundingContext,
            ToolAccessPolicy toolAccessPolicy
    ) {
        this(
                history,
                text,
                images,
                groundingContext,
                toolAccessPolicy,
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
                ToolAccessPolicy.unrestricted(),
                ChatProviderPolicy.STANDARD);
    }

    public ChatRequest(List<ChatMessage> history, String text, List<ImageInput> images) {
        this(
                history,
                text,
                images,
                "",
                ToolAccessPolicy.unrestricted(),
                ChatProviderPolicy.STANDARD);
    }
}
