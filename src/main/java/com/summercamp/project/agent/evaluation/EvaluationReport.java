package com.summercamp.project.agent.evaluation;

import java.util.List;

public record EvaluationReport(boolean valid, List<String> issues) {

    public EvaluationReport {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }
}
