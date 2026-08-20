package com.summercamp.project.speech;

public record PreparedAudio(byte[] data, String fileName, String mediaType) {

    public PreparedAudio {
        data = data.clone();
    }
}
