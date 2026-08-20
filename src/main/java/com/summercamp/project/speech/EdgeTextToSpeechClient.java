package com.summercamp.project.speech;

import com.summercamp.project.llm.LlmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class EdgeTextToSpeechClient implements TextToSpeechClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(EdgeTextToSpeechClient.class);
    private static final int MAX_ATTEMPTS = 2;
    private static final int MP3_ENCODE_TYPE = 7;
    private static final int BITS_PER_SAMPLE = 16;

    private final EdgeSpeechEngine engine;

    EdgeTextToSpeechClient(EdgeSpeechEngine engine) {
        this.engine = engine;
    }

    @Override
    public SynthesizedSpeech synthesize(String text) {
        if (text == null || text.isBlank()) {
            throw new LlmException("语音合成文本不能为空");
        }
        LlmException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                Mp3Audio.Info info = Mp3Audio.inspectAndTrim(engine.synthesize(text));
                LOGGER.info("免费 Edge TTS 合成成功：{} 字，{} 字节，约 {} 毫秒",
                        text.length(), info.data().length, info.durationMillis());
                return new SynthesizedSpeech(
                        info.data(),
                        "AI语音回复.mp3",
                        info.durationMillis(),
                        info.sampleRate(),
                        MP3_ENCODE_TYPE,
                        BITS_PER_SAMPLE,
                        text);
            } catch (LlmException exception) {
                lastFailure = exception;
                if (attempt < MAX_ATTEMPTS) {
                    LOGGER.warn("免费 Edge TTS 第 {} 次合成失败，将自动重试：{}",
                            attempt, exception.getMessage());
                }
            }
        }
        throw lastFailure == null
                ? new LlmException("免费 Edge TTS 语音合成失败")
                : lastFailure;
    }
}
