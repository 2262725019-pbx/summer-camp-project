package com.summercamp.project.agent.artifact;

import java.time.Instant;

public record HealthPlanPage(
        String id,
        String title,
        String content,
        Instant createdAt,
        Instant expiresAt) {
}
