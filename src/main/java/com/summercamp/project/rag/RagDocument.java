package com.summercamp.project.rag;

import java.util.List;

public record RagDocument(String id, String title, List<String> keywords, String content) {

    public RagDocument {
        keywords = List.copyOf(keywords);
    }
}
