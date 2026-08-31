package com.summercamp.project.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.persistence")
public record AgentPersistenceProperties(
        boolean enabled,
        String databasePath) {

    public Path path() {
        String configured = databasePath == null || databasePath.isBlank()
                ? "runtime/agent-state.db"
                : databasePath.strip();
        return Path.of(configured).toAbsolutePath().normalize();
    }
}
