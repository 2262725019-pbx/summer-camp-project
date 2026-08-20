package com.summercamp.project.speech;

import com.summercamp.project.config.EdgeTtsProperties;
import com.summercamp.project.llm.LlmException;
import io.github.whitemagic2014.tts.TTS;
import io.github.whitemagic2014.tts.TTSVoice;
import io.github.whitemagic2014.tts.bean.Voice;
import java.io.ByteArrayOutputStream;
import org.springframework.stereotype.Component;

@Component
class MicrosoftEdgeSpeechEngine implements EdgeSpeechEngine {

    private final EdgeTtsProperties properties;
    private final Voice voice;

    MicrosoftEdgeSpeechEngine(EdgeTtsProperties properties) {
        this.properties = properties;
        this.voice = TTSVoice.provides().stream()
                .filter(candidate -> properties.voice().equals(candidate.getShortName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "没有找到 Edge TTS 音色：" + properties.voice()));
    }

    @Override
    public byte[] synthesize(String text) {
        properties.validate();
        try {
            ByteArrayOutputStream audio = new TTS(voice, text)
                    .findHeadHook()
                    .isRateLimited(true)
                    .voiceRate(properties.rate())
                    .voicePitch(properties.pitch())
                    .voiceVolume(properties.volume())
                    .connectTimeout(properties.connectTimeoutMillis())
                    .formatMp3()
                    .transToAudioStream();
            if (audio == null || audio.size() == 0) {
                throw new LlmException("Microsoft Edge TTS 没有返回音频");
            }
            return audio.toByteArray();
        } catch (LlmException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new LlmException("Microsoft Edge TTS 语音合成失败", exception);
        }
    }
}
