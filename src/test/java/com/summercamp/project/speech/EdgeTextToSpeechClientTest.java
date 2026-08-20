package com.summercamp.project.speech;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.summercamp.project.config.EdgeTtsProperties;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

class EdgeTextToSpeechClientTest {

    @Test
    void trimsEdgeProtocolPrefixAndReturnsWechatMp3Metadata() {
        byte[] mp3 = mpeg2Layer3Frames(2);
        byte[] prefix = "Path:audio\r\n\r\nxx"
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] response = new byte[prefix.length + mp3.length];
        System.arraycopy(prefix, 0, response, 0, prefix.length);
        System.arraycopy(mp3, 0, response, prefix.length, mp3.length);
        EdgeTextToSpeechClient client = new EdgeTextToSpeechClient(text -> response);

        SynthesizedSpeech speech = client.synthesize("你好");

        assertEquals(7, speech.encodeType());
        assertEquals(24_000, speech.sampleRate());
        assertEquals(48, speech.durationMillis());
        assertEquals("AI语音回复.mp3", speech.fileName());
        assertEquals(mp3.length, speech.data().length);
        assertTrue((speech.data()[0] & 0xFF) == 0xFF);
    }

    @Test
    void retriesOnceWhenEdgeServiceReturnsNoAudio() {
        byte[] response = mpeg2Layer3Frames(2);
        AtomicInteger calls = new AtomicInteger();
        EdgeTextToSpeechClient client = new EdgeTextToSpeechClient(text -> {
            if (calls.incrementAndGet() == 1) {
                throw new com.summercamp.project.llm.LlmException("temporary empty audio");
            }
            return response;
        });

        SynthesizedSpeech speech = client.synthesize("重试测试");

        assertEquals(2, calls.get());
        assertEquals(7, speech.encodeType());
        assertTrue(speech.data().length > 0);
    }

    @Test
    @EnabledIfSystemProperty(named = "edge.tts.live", matches = "true")
    void synthesizesChineseSpeechFromMicrosoftEdge() {
        EdgeTtsProperties properties = new EdgeTtsProperties(
                "zh-CN-XiaoxiaoNeural",
                "+0%",
                "+0Hz",
                "+0%",
                Duration.ofSeconds(20));
        EdgeTextToSpeechClient client = new EdgeTextToSpeechClient(
                new MicrosoftEdgeSpeechEngine(properties));

        SynthesizedSpeech speech = client.synthesize("你好，这是免费的语音回复测试。");

        assertEquals(7, speech.encodeType());
        assertEquals(24_000, speech.sampleRate());
        assertTrue(speech.data().length > 1_000);
        assertTrue(speech.durationMillis() > 500,
                "duration=" + speech.durationMillis() + ", bytes=" + speech.data().length);
    }

    private byte[] mpeg2Layer3Frames(int count) {
        int header = 0xFFE00000
                | (2 << 19)
                | (1 << 17)
                | (1 << 16)
                | (6 << 12)
                | (1 << 10)
                | (3 << 6);
        int frameLength = 144;
        byte[] data = new byte[frameLength * count];
        for (int frame = 0; frame < count; frame++) {
            int offset = frame * frameLength;
            data[offset] = (byte) (header >>> 24);
            data[offset + 1] = (byte) (header >>> 16);
            data[offset + 2] = (byte) (header >>> 8);
            data[offset + 3] = (byte) header;
        }
        return data;
    }
}
