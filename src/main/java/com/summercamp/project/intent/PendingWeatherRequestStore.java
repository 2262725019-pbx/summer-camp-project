package com.summercamp.project.intent;

import com.summercamp.project.weather.WeatherPeriod;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class PendingWeatherRequestStore {

    private static final Duration TTL = Duration.ofMinutes(5);

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock;

    public PendingWeatherRequestStore() {
        this(Clock.systemUTC());
    }

    PendingWeatherRequestStore(Clock clock) {
        this.clock = clock;
    }

    public void remember(String userId, WeatherPeriod period) {
        entries.put(userId, new Entry(period, clock.instant().plus(TTL)));
    }

    public Optional<WeatherPeriod> consume(String userId) {
        Entry entry = entries.remove(userId);
        if (entry == null || !entry.expiresAt().isAfter(clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(entry.period());
    }

    public void clear(String userId) {
        entries.remove(userId);
    }

    public void cleanupExpired() {
        Instant now = clock.instant();
        entries.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    private record Entry(WeatherPeriod period, Instant expiresAt) {
    }
}
