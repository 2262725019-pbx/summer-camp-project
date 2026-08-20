package com.summercamp.project.llm;

import java.util.Objects;

public record ImageInput(byte[] data, String mediaType) {

    public ImageInput {
        data = Objects.requireNonNull(data, "data").clone();
        mediaType = Objects.requireNonNull(mediaType, "mediaType");
    }

    @Override
    public byte[] data() {
        return data.clone();
    }
}
