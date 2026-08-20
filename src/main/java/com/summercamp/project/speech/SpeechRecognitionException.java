package com.summercamp.project.speech;

import com.summercamp.project.llm.LlmException;

public class SpeechRecognitionException extends LlmException {

    public SpeechRecognitionException(String message) {
        super(message);
    }

    public SpeechRecognitionException(String message, Throwable cause) {
        super(message, cause);
    }
}
