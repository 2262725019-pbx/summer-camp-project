package com.summercamp.project.agent.artifact;

import java.util.List;

public record HealthPlanArtifact(
        String title,
        String content,
        List<String> sourceDocumentIds,
        List<String> warnings) {

    public HealthPlanArtifact {
        title = title == null ? "七日健康生活计划" : title.strip();
        content = content == null ? "" : content.strip();
        sourceDocumentIds = sourceDocumentIds == null ? List.of() : List.copyOf(sourceDocumentIds);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
