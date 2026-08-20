package com.summercamp.project.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "weather.amap")
public record WeatherProperties(String baseUrl, String apiKey, Duration timeout) {

    public URI endpoint(String pathAndQuery) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String path = pathAndQuery.startsWith("/") ? pathAndQuery : "/" + pathAndQuery;
        return URI.create(base + path);
    }

    public void validate() {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("AMAP_BASE_URL 未配置");
        }
        if (apiKey == null || apiKey.isBlank() || apiKey.startsWith("PASTE_")) {
            throw new IllegalStateException("AMAP_API_KEY 未配置，请填写高德 Web 服务 Key");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalStateException("AMAP_TIMEOUT 必须大于 0");
        }
    }
}
