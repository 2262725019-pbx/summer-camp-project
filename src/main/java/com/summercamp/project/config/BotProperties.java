package com.summercamp.project.config;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bot")
public record BotProperties(
        boolean enabled,
        long imageMaxBytes,
        long voiceMaxBytes,
        Duration voiceMaxDuration,
        String voiceReplyMode,
        Path qrCodePath,
        boolean qrCodeAutoOpen,
        Duration pollRetryDelay,
        int messagePoolSize) {

    public void validate() {
        if (imageMaxBytes <= 0) {
            throw new IllegalStateException("BOT_IMAGE_MAX_BYTES 必须大于 0");
        }
        if (voiceMaxBytes <= 0) {
            throw new IllegalStateException("BOT_VOICE_MAX_BYTES 必须大于 0");
        }
        if (voiceMaxDuration == null || voiceMaxDuration.isNegative() || voiceMaxDuration.isZero()) {
            throw new IllegalStateException("BOT_VOICE_MAX_DURATION 必须大于 0");
        }
        if (!"file".equalsIgnoreCase(voiceReplyMode)
                && !"native".equalsIgnoreCase(voiceReplyMode)) {
            throw new IllegalStateException("BOT_VOICE_REPLY_MODE 只能配置为 file 或 native");
        }
        if (qrCodePath == null) {
            throw new IllegalStateException("BOT_QR_CODE_PATH 未配置");
        }
        if (pollRetryDelay == null || pollRetryDelay.isNegative()) {
            throw new IllegalStateException("BOT_POLL_RETRY_DELAY 配置无效");
        }
        if (messagePoolSize <= 0) {
            throw new IllegalStateException("BOT_MESSAGE_POOL_SIZE 必须大于 0");
        }
    }

    public boolean sendVoiceAsFile() {
        return "file".equalsIgnoreCase(voiceReplyMode);
    }
}
