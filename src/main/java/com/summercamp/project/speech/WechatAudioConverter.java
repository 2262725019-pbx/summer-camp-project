package com.summercamp.project.speech;

import io.github.kasukusakura.silkcodec.SilkCoder;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.springframework.stereotype.Component;

@Component
public class WechatAudioConverter {

    private static final int PCM = 1;
    private static final int SILK = 6;
    private static final int MP3 = 7;
    private static final int DEFAULT_SAMPLE_RATE = 24_000;

    public PreparedAudio prepareForAsr(VoiceInput input) {
        return switch (input.encodeType()) {
            case SILK -> silkToWav(input.data());
            case PCM -> pcmToWav(input.data(), positiveOrDefault(input.sampleRate()));
            case MP3 -> new PreparedAudio(input.data(), "wechat-voice.mp3", "audio/mpeg");
            default -> throw new SpeechRecognitionException(
                    "暂不支持该微信语音编码，encodeType=" + input.encodeType());
        };
    }

    private PreparedAudio silkToWav(byte[] silk) {
        try (ByteArrayInputStream source = new ByteArrayInputStream(silk);
                ByteArrayOutputStream pcm = new ByteArrayOutputStream()) {
            SilkCoder.decode(source, pcm);
            if (pcm.size() == 0) {
                throw new SpeechRecognitionException("SILK 语音解码结果为空");
            }
            return pcmToWav(pcm.toByteArray(), DEFAULT_SAMPLE_RATE);
        } catch (IOException | LinkageError exception) {
            throw new SpeechRecognitionException("无法将微信 SILK 语音转换为 WAV", exception);
        }
    }

    private PreparedAudio pcmToWav(byte[] pcm, int sampleRate) {
        return new PreparedAudio(
                WavAudio.fromPcm16Mono(pcm, sampleRate),
                "wechat-voice.wav",
                "audio/wav");
    }

    private int positiveOrDefault(int sampleRate) {
        return sampleRate > 0 ? sampleRate : DEFAULT_SAMPLE_RATE;
    }
}
