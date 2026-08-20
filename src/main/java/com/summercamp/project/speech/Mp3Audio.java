package com.summercamp.project.speech;

import com.summercamp.project.llm.LlmException;
import java.io.ByteArrayOutputStream;

final class Mp3Audio {

    private static final int[] MPEG1_LAYER3_BITRATES = {
            0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0
    };
    private static final int[] MPEG2_LAYER3_BITRATES = {
            0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0
    };
    private static final int[] SAMPLE_RATES = {44_100, 48_000, 32_000};

    private Mp3Audio() {
    }

    static Info inspectAndTrim(byte[] source) {
        ByteArrayOutputStream cleanAudio = new ByteArrayOutputStream(source.length);
        Header first = null;
        long totalSamples = 0;
        int offset = 0;
        while (offset + 4 <= source.length) {
            Header header = headerAt(source, offset);
            if (header == null || header.frameLength() <= 0
                    || offset + header.frameLength() > source.length) {
                offset++;
                continue;
            }
            if (first == null) {
                first = header;
            }
            cleanAudio.write(source, offset, header.frameLength());
            totalSamples += header.samplesPerFrame();
            offset += header.frameLength();
        }
        if (first == null || totalSamples == 0) {
            throw new LlmException("Edge TTS 返回的内容不是有效 MP3 音频");
        }
        int durationMillis = Math.max(
                1,
                (int) Math.round(totalSamples * 1_000.0 / first.sampleRate()));
        return new Info(cleanAudio.toByteArray(), first.sampleRate(), durationMillis);
    }

    private static Header headerAt(byte[] data, int offset) {
        if (offset < 0 || offset + 4 > data.length) {
            return null;
        }
        int header = (data[offset] & 0xFF) << 24
                | (data[offset + 1] & 0xFF) << 16
                | (data[offset + 2] & 0xFF) << 8
                | (data[offset + 3] & 0xFF);
        if ((header & 0xFFE00000) != 0xFFE00000) {
            return null;
        }
        int version = (header >>> 19) & 0x3;
        int layer = (header >>> 17) & 0x3;
        int bitrateIndex = (header >>> 12) & 0xF;
        int sampleRateIndex = (header >>> 10) & 0x3;
        if (version == 1 || layer != 1 || bitrateIndex == 0 || bitrateIndex == 15 || sampleRateIndex == 3) {
            return null;
        }
        boolean mpeg1 = version == 3;
        int sampleRate = SAMPLE_RATES[sampleRateIndex] / (mpeg1 ? 1 : version == 2 ? 2 : 4);
        int bitrate = (mpeg1 ? MPEG1_LAYER3_BITRATES : MPEG2_LAYER3_BITRATES)[bitrateIndex] * 1_000;
        int padding = (header >>> 9) & 1;
        int frameLength = (mpeg1 ? 144 : 72) * bitrate / sampleRate + padding;
        return new Header(sampleRate, frameLength, mpeg1 ? 1_152 : 576);
    }

    record Info(byte[] data, int sampleRate, int durationMillis) {
    }

    private record Header(int sampleRate, int frameLength, int samplesPerFrame) {
    }
}
