package com.summercamp.project.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "result-page")
public record ResultPageProperties(String publicBaseUrl, int port, Duration ttl) {

    public void validate() {
        if (port < 1 || port > 65_535) {
            throw new IllegalStateException("RESULT_PAGE_PORT 必须在 1 到 65535 之间");
        }
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalStateException("RESULT_PAGE_TTL 必须大于 0");
        }
    }
}
