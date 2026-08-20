package com.summercamp.project.llm;

import java.util.List;

public interface ImageGenerationClient {
    GeneratedImage generate(List<ChatMessage> history, String prompt);
}
