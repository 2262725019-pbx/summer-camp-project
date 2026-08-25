package com.summercamp.project.agent.store;

import com.summercamp.project.config.HealthAgentProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class PendingHealthGoalStore {

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final HealthAgentProperties properties;
    private final Clock clock;

    @Autowired
    public PendingHealthGoalStore(HealthAgentProperties properties) {
        this(properties, Clock.systemUTC());
    }

    PendingHealthGoalStore(HealthAgentProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        properties.validate();
    }

    public void remember(String userId, String accumulatedText) {
        entries.put(userId, new Entry(
                accumulatedText.strip(),
                clock.instant().plus(properties.pendingTtl())));
    }

    public Optional<String> get(String userId) {
        Entry entry = entries.get(userId);
        if (entry == null) {
            return Optional.empty();
        }
        if (!entry.expiresAt().isAfter(clock.instant())) {
            entries.remove(userId, entry);
            return Optional.empty();
        }
        return Optional.of(entry.accumulatedText());
    }

    public void clear(String userId) {
        entries.remove(userId);
    }

    private record Entry(String accumulatedText, Instant expiresAt) {
    }
}
