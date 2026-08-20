package com.summercamp.project.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class InMemoryConversationMemoryStoreTest {

    @Test
    void shouldIsolateUsersAndKeepAtMostTenRounds() {
        MutableClock clock = new MutableClock();
        InMemoryConversationMemoryStore store = new InMemoryConversationMemoryStore(clock);

        for (int index = 0; index < 12; index++) {
            store.recordExchange("user-a", "question-" + index, "answer-" + index);
        }
        store.recordExchange("user-b", "other-question", "other-answer");

        assertEquals(20, store.history("user-a").size());
        assertEquals("question-2", store.history("user-a").getFirst().content());
        assertEquals(2, store.history("user-b").size());
    }

    @Test
    void shouldExpireHistoryAfterThirtyMinutes() {
        MutableClock clock = new MutableClock();
        InMemoryConversationMemoryStore store = new InMemoryConversationMemoryStore(clock);
        store.recordExchange("user", "hello", "hi");

        clock.advance(Duration.ofMinutes(31));

        assertTrue(store.history("user").isEmpty());
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-08-17T00:00:00Z");

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
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
