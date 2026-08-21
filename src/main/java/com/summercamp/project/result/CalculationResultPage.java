package com.summercamp.project.result;

import java.time.Instant;

public record CalculationResultPage(
        String id,
        String title,
        String expression,
        String result,
        Instant createdAt,
        Instant expiresAt) {
}
