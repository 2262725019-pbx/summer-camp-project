package com.summercamp.project.wechat;

import com.summercamp.project.llm.ImageInput;
import com.summercamp.project.speech.VoiceInput;
import java.util.List;

public record InboundMessage(
        String messageId,
        String userId,
        String text,
        List<ImageInput> images,
        List<VoiceInput> voices,
        boolean imageTooLarge,
        boolean voiceTooLarge,
        boolean unsupportedMedia) {

    public InboundMessage {
        text = text == null ? "" : text;
        images = List.copyOf(images);
        voices = List.copyOf(voices);
    }

    public boolean hasSupportedContent() {
        return !text.isBlank() || !images.isEmpty() || !voices.isEmpty();
    }

    public boolean isVoiceMessage() {
        return !voices.isEmpty();
    }

    public InboundMessage withText(String newText) {
        return new InboundMessage(
                messageId,
                userId,
                newText,
                images,
                voices,
                imageTooLarge,
                voiceTooLarge,
                unsupportedMedia);
    }
}
