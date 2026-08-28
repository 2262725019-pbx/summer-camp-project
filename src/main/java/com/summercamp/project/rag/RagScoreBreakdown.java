package com.summercamp.project.rag;

public record RagScoreBreakdown(
        int titleScore,
        int keywordScore,
        int contentScore,
        int coverageScore,
        int totalScore) {

    public RagScoreBreakdown {
        if (titleScore < 0 || keywordScore < 0 || contentScore < 0 || coverageScore < 0) {
            throw new IllegalArgumentException("RAG score components cannot be negative");
        }
        if (totalScore != titleScore + keywordScore + contentScore + coverageScore) {
            throw new IllegalArgumentException("RAG total score must equal its components");
        }
    }

    public static RagScoreBreakdown legacy(int score) {
        return new RagScoreBreakdown(0, 0, 0, score, score);
    }
}
