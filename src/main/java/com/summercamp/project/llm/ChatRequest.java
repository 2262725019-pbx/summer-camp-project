package com.summercamp.project.llm;

import java.util.List;

public record ChatRequest(List<ChatMessage> history, String text, List<ImageInput> images) {

    public ChatRequest {
        history = List.copyOf(history);
        images = List.copyOf(images);
        text = text == null ? "" : text;
    }
}
