package com.summercamp.project.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.optimization")
public record AgentOptimizationProperties(
        boolean cacheEnabled,
        Duration weatherCacheTtl,
        Duration ragCacheTtl,
        int cacheMaxEntries,
        int maxRagPromptChars,
        int maxHistoryMessages,
        int maxHistoryChars) {

    public void validate() {
        positive(weatherCacheTtl, "weather-cache-ttl");
        positive(ragCacheTtl, "rag-cache-ttl");
        if (cacheMaxEntries < 1 || maxRagPromptChars < 100
                || maxHistoryMessages < 0 || maxHistoryChars < 0) {
            throw new IllegalStateException("Agent 优化配置必须使用有效的正数范围");
        }
    }

    private void positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException("agent.optimization." + name + " 必须大于 0");
        }
    }
}
