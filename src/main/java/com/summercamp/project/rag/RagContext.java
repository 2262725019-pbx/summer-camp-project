package com.summercamp.project.rag;

import java.util.List;

public record RagContext(List<Hit> hits, String promptContext) {

    public RagContext {
        hits = List.copyOf(hits);
        promptContext = promptContext == null ? "" : promptContext.strip();
    }

    public static RagContext empty() {
        return new RagContext(List.of(), "");
    }

    public boolean matched() {
        return !hits.isEmpty();
    }

    public List<String> documentIds() {
        return hits.stream().map(hit -> hit.document().id()).toList();
    }

    public record Hit(RagDocument document, int score, RagScoreBreakdown breakdown) {

        public Hit(RagDocument document, int score) {
            this(document, score, RagScoreBreakdown.legacy(score));
        }

        public Hit(RagDocument document, RagScoreBreakdown breakdown) {
            this(document, breakdown.totalScore(), breakdown);
        }
    }
}
