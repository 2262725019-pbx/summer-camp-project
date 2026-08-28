package com.summercamp.project.intent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.summercamp.project.weather.WeatherPeriod;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PendingWeatherRequestStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void consumesPendingRequestWithinFiveMinutes() {
        PendingWeatherRequestStore store = new PendingWeatherRequestStore(
                Clock.fixed(Instant.parse("2026-08-18T03:00:00Z"), ZoneOffset.UTC));
        store.remember("user-1", WeatherPeriod.TOMORROW);

        assertEquals(WeatherPeriod.TOMORROW, store.consume("user-1").orElseThrow());
        assertTrue(store.consume("user-1").isEmpty());
    }

    @Test
    void expiresPendingRequestAfterFiveMinutes() {
        Instant start = Instant.parse("2026-08-18T03:00:00Z");
        MutableClock clock = new MutableClock(start);
        PendingWeatherRequestStore store = new PendingWeatherRequestStore(clock);
        store.remember("user-1", WeatherPeriod.THREE_DAYS);
        clock.instant = start.plusSeconds(301);

        assertTrue(store.consume("user-1").isEmpty());
    }

    @Test
    void shouldPersistPendingRequestAcrossInstances() {
        Instant now = Instant.parse("2026-08-18T03:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        String file = tempDir.resolve("pending-weather.json").toString();

        PendingWeatherRequestStore first = new PendingWeatherRequestStore(clock, file);
        first.remember("user-1", WeatherPeriod.TOMORROW);

        PendingWeatherRequestStore second = new PendingWeatherRequestStore(clock, file);
        assertEquals(WeatherPeriod.TOMORROW, second.consume("user-1").orElseThrow());
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
