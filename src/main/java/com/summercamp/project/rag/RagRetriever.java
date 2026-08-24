package com.summercamp.project.rag;

public interface RagRetriever {

    RagContext retrieve(String query);
}
