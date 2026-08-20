package com.summercamp.project.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "speech.edge-tts")
public record EdgeTtsProperties(
        String voice,
        String rate,
        String pitch,
        String volume,
        Duration connectTimeout) {

    public void validate() {
        requireText(voice, "EDGE_TTS_VOICE");
        requireText(rate, "EDGE_TTS_RATE");
        requireText(pitch, "EDGE_TTS_PITCH");
        requireText(volume, "EDGE_TTS_VOLUME");
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()) {
            throw new IllegalStateException("EDGE_TTS_CONNECT_TIMEOUT 必须大于 0");
        }
        if (connectTimeout.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalStateException("EDGE_TTS_CONNECT_TIMEOUT 不能超过 2 分钟");
        }
    }

    public int connectTimeoutMillis() {
        return Math.toIntExact(connectTimeout.toMillis());
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " 未配置");
        }
    }
}
