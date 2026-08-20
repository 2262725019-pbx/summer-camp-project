package com.summercamp.project.llm;

import java.util.Objects;

public record GeneratedImage(byte[] data, String mediaType, String fileName) {

    public GeneratedImage {
        data = Objects.requireNonNull(data, "data").clone();
        mediaType = Objects.requireNonNull(mediaType, "mediaType");
        fileName = Objects.requireNonNull(fileName, "fileName");
    }

    @Override
    public byte[] data() {
        return data.clone();
    }
}
