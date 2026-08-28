package com.summercamp.project.rag;

import java.util.List;

public record RagDocument(String id, String title, List<String> keywords, String content, String source) {

    public RagDocument {
        if (id == null || id.isBlank() || title == null || title.isBlank() || content == null) {
            throw new IllegalArgumentException("RAG document id, title and content are required");
        }
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        source = source == null || source.isBlank() ? "PROJECT_DOC" : source.strip();
    }

    public RagDocument(String id, String title, List<String> keywords, String content) {
        this(id, title, keywords, content, "PROJECT_DOC");
    }
}
