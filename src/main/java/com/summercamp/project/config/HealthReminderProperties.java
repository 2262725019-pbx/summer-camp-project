package com.summercamp.project.config;

import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.health.reminder")
public record HealthReminderProperties(
        boolean enabled,
        Duration scanInterval,
        Duration planTtl,
        String zoneId) {

    public void validate() {
        if (scanInterval == null || scanInterval.isZero() || scanInterval.isNegative()
                || planTtl == null || planTtl.isZero() || planTtl.isNegative()) {
            throw new IllegalStateException("健康提醒的扫描间隔和计划有效期必须大于 0");
        }
        zone();
    }

    public ZoneId zone() {
        return ZoneId.of(zoneId == null || zoneId.isBlank() ? "Asia/Shanghai" : zoneId);
    }
}
