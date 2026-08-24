package com.summercamp.project.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rag")
public record RagProperties(
        boolean enabled,
        int topK,
        int minScore,
        int maxContextChars) {

    public RagProperties {
        if (topK <= 0) {
            throw new IllegalArgumentException("rag.top-k 必须大于 0");
        }
        if (minScore <= 0) {
            throw new IllegalArgumentException("rag.min-score 必须大于 0");
        }
        if (maxContextChars < 200) {
            throw new IllegalArgumentException("rag.max-context-chars 不能小于 200");
        }
    }
}
