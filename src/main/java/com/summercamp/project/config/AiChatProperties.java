package com.summercamp.project.config;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ai.zhipu")
public record AiChatProperties(
        String baseUrl,
        String chatApiPath,
        String imageApiPath,
        String asrApiPath,
        String apiKey,
        String textModel,
        List<String> textFallbackModels,
        String visionModel,
        List<String> visionFallbackModels,
        String imageModel,
        String imageSize,
        String asrModel,
        Duration timeout,
        Duration asrTimeout,
        Duration imageTimeout,
        boolean toolFilterEnabled) {

    public URI chatEndpoint() {
        return endpoint(chatApiPath);
    }

    public URI imageEndpoint() {
        return endpoint(imageApiPath);
    }

    public URI asrEndpoint() {
        return endpoint(asrApiPath);
    }

    private URI endpoint(String apiPath) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String path = apiPath.startsWith("/") ? apiPath : "/" + apiPath;
        return URI.create(base + path);
    }

    public void validate() {
        requireText(baseUrl, "ZHIPU_BASE_URL");
        requireText(chatApiPath, "ZHIPU_CHAT_API_PATH");
        requireText(imageApiPath, "ZHIPU_IMAGE_API_PATH");
        requireText(asrApiPath, "ZHIPU_ASR_API_PATH");
        requireText(apiKey, "ZHIPU_API_KEY");
        requireText(textModel, "ZHIPU_TEXT_MODEL");
        requireText(visionModel, "ZHIPU_VISION_MODEL");
        requireText(imageModel, "ZHIPU_IMAGE_MODEL");
        requireText(imageSize, "ZHIPU_IMAGE_SIZE");
        requireText(asrModel, "ZHIPU_ASR_MODEL");
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalStateException("ZHIPU_TIMEOUT 必须大于 0");
        }
        if (asrTimeout == null || asrTimeout.isNegative() || asrTimeout.isZero()) {
            throw new IllegalStateException("ZHIPU_ASR_TIMEOUT 必须大于 0");
        }
        if (imageTimeout == null || imageTimeout.isNegative() || imageTimeout.isZero()) {
            throw new IllegalStateException("ZHIPU_IMAGE_TIMEOUT 必须大于 0");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " 未配置");
        }
    }
}
