package com.summercamp.project.agent;

import java.util.List;

/** Content-light validation result. It intentionally carries no generated answer text. */
public record FinalPlanValidationResult(
        boolean valid,
        List<FinalPlanValidationIssueCode> issues
) {
    public FinalPlanValidationResult {
        issues = issues == null ? List.of() : List.copyOf(issues);
        if (valid != issues.isEmpty()) {
            throw new IllegalArgumentException("valid must match whether issues is empty");
        }
    }

    public static FinalPlanValidationResult validResult() {
        return new FinalPlanValidationResult(true, List.of());
    }

    public static FinalPlanValidationResult invalid(
            List<FinalPlanValidationIssueCode> issues
    ) {
        if (issues == null || issues.isEmpty()) {
            throw new IllegalArgumentException("invalid result must contain issues");
        }
        return new FinalPlanValidationResult(false, issues);
    }
}
