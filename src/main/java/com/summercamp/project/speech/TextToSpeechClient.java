package com.summercamp.project.speech;

public interface TextToSpeechClient {

    SynthesizedSpeech synthesize(String text);
}
