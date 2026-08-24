package com.summercamp.project.skill;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class PendingSkillStore {

    private static final Duration TTL = Duration.ofMinutes(5);

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock;

    public PendingSkillStore() {
        this(Clock.systemUTC());
    }

    PendingSkillStore(Clock clock) {
        this.clock = clock;
    }

    public void remember(String userId, String skillName) {
        entries.put(userId, new Entry(skillName, clock.instant().plus(TTL)));
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
        return Optional.of(entry.skillName());
    }

    public void clear(String userId) {
        entries.remove(userId);
    }

    private record Entry(String skillName, Instant expiresAt) {
    }
}
