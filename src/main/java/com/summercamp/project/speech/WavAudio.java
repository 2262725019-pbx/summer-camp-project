package com.summercamp.project.speech;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class WavAudio {

    private static final int HEADER_SIZE = 44;

    private WavAudio() {
    }

    public static byte[] fromPcm16Mono(byte[] pcm, int sampleRate) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("采样率必须大于 0");
        }
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE + pcm.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(new byte[] {'R', 'I', 'F', 'F'});
        buffer.putInt(36 + pcm.length);
        buffer.put(new byte[] {'W', 'A', 'V', 'E'});
        buffer.put(new byte[] {'f', 'm', 't', ' '});
        buffer.putInt(16);
        buffer.putShort((short) 1);
        buffer.putShort((short) 1);
        buffer.putInt(sampleRate);
        buffer.putInt(sampleRate * 2);
        buffer.putShort((short) 2);
        buffer.putShort((short) 16);
        buffer.put(new byte[] {'d', 'a', 't', 'a'});
        buffer.putInt(pcm.length);
        buffer.put(pcm);
        return buffer.array();
    }
}
