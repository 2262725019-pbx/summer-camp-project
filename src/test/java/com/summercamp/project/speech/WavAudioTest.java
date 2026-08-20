package com.summercamp.project.speech;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.kasukusakura.silkcodec.SilkCoder;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class WavAudioTest {

    @Test
    void wrapsPcmWithAValidMonoPcmWavHeader() {
        byte[] pcm = {1, 2, 3, 4};

        byte[] wav = WavAudio.fromPcm16Mono(pcm, 24_000);

        assertEquals("RIFF", text(wav, 0, 4));
        assertEquals("WAVE", text(wav, 8, 4));
        assertEquals("fmt ", text(wav, 12, 4));
        assertEquals("data", text(wav, 36, 4));
        assertEquals(1, littleEndianShort(wav, 22));
        assertEquals(24_000, littleEndianInt(wav, 24));
        assertEquals(16, littleEndianShort(wav, 34));
        assertEquals(pcm.length, littleEndianInt(wav, 40));
        assertArrayEquals(pcm, java.util.Arrays.copyOfRange(wav, 44, wav.length));
    }

    @Test
    void convertsWechatSilkToWav() throws Exception {
        byte[] pcm = new byte[24_000 * 2 / 5];
        ByteArrayOutputStream silk = new ByteArrayOutputStream();
        SilkCoder.encode(new ByteArrayInputStream(pcm), silk, 24_000, 24_000);

        PreparedAudio audio = new WechatAudioConverter().prepareForAsr(
                new VoiceInput(silk.toByteArray(), "", 6, 16, 24_000, 200));

        assertEquals("audio/wav", audio.mediaType());
        assertEquals("RIFF", text(audio.data(), 0, 4));
        assertEquals(24_000, littleEndianInt(audio.data(), 24));
        assertTrue(audio.data().length > 44);
    }

    private String text(byte[] data, int offset, int length) {
        return new String(data, offset, length, StandardCharsets.US_ASCII);
    }

    private int littleEndianShort(byte[] data, int offset) {
        return Short.toUnsignedInt(ByteBuffer.wrap(data, offset, 2)
                .order(ByteOrder.LITTLE_ENDIAN)
                .getShort());
    }

    private int littleEndianInt(byte[] data, int offset) {
        return ByteBuffer.wrap(data, offset, 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .getInt();
    }
}
