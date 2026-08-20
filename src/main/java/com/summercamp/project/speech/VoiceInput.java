package com.summercamp.project.speech;

public record VoiceInput(
        byte[] data,
        String transcript,
        int encodeType,
        int bitsPerSample,
        int sampleRate,
        int durationMillis) {

    public VoiceInput {
        data = data == null ? new byte[0] : data.clone();
        transcript = transcript == null ? "" : transcript.strip();
    }

    public boolean hasTranscript() {
        return !transcript.isBlank();
    }
}
