package com.summercamp.project.agent.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.summercamp.project.config.HealthAgentProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class PendingHealthGoalStoreTest {

    @Test
    void keepsPendingInputWithinTheConfiguredWindow() {
        PendingHealthGoalStore store = storeAt("2026-08-25T00:00:00Z", Duration.ofMinutes(30));

        store.remember("user-1", "增肌计划");

        assertThat(store.get("user-1")).contains("增肌计划");
    }

    @Test
    void expiresOldPendingInput() {
        HealthAgentProperties properties = new HealthAgentProperties(true, Duration.ofMinutes(30), false);
        MutableClock clock = new MutableClock(Instant.parse("2026-08-25T00:00:00Z"));
        PendingHealthGoalStore store = new PendingHealthGoalStore(properties, clock);
        store.remember("user-1", "增肌计划");

        clock.advance(Duration.ofMinutes(31));

        assertThat(store.get("user-1")).isEmpty();
    }

    private PendingHealthGoalStore storeAt(String instant, Duration ttl) {
        return new PendingHealthGoalStore(
                new HealthAgentProperties(true, ttl, false),
                Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
