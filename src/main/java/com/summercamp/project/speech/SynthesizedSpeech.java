package com.summercamp.project.speech;

public record SynthesizedSpeech(
        byte[] data,
        String fileName,
        int durationMillis,
        int sampleRate,
        int encodeType,
        int bitsPerSample,
        String transcript) {

    public SynthesizedSpeech {
        data = data == null ? new byte[0] : data.clone();
        transcript = transcript == null ? "" : transcript;
    }
}
