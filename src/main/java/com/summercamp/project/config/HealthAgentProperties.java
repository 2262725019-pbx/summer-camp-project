package com.summercamp.project.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.health")
public record HealthAgentProperties(
        boolean enabled,
        Duration pendingTtl,
        boolean generateCover) {

    public void validate() {
        if (pendingTtl == null || pendingTtl.isZero() || pendingTtl.isNegative()) {
            throw new IllegalStateException("AGENT_HEALTH_PENDING_TTL 必须大于 0");
        }
    }
}
