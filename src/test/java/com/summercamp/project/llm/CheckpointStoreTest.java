package com.summercamp.project.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CheckpointStoreTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private Clock fixed(String iso) {
        return Clock.fixed(Instant.parse(iso), ZoneOffset.UTC);
    }

    @Test
    void shouldSaveLoadAndClearCheckpoint() {
        CheckpointStore store = new CheckpointStore(
                objectMapper, null, fixed("2026-08-27T00:00:00Z"));

        store.save("user-a", "text-model", objectMapper.createObjectNode(), 3, false);

        CheckpointStore.TaskCheckpoint loaded = store.load("user-a").orElseThrow();
        assertEquals("text-model", loaded.model());
        assertEquals(3, loaded.round());
        store.clear("user-a");
        assertTrue(store.load("user-a").isEmpty());
    }

    @Test
    void shouldExpireCheckpointAfterThirtyMinutes() {
        Instant start = Instant.parse("2026-08-27T00:00:00Z");
        MutableClock clock = new MutableClock(start);
        CheckpointStore store = new CheckpointStore(objectMapper, null, clock);
        store.save("user-a", "text-model", objectMapper.createObjectNode(), 0, false);

        clock.instant = start.plus(Duration.ofMinutes(31));

        assertTrue(store.load("user-a").isEmpty());
    }

    @Test
    void shouldPersistCheckpointAcrossInstances() {
        Clock clock = fixed("2026-08-27T00:00:00Z");
        String file = tempDir.resolve("checkpoints.json").toString();

        CheckpointStore first = new CheckpointStore(objectMapper, Path.of(file), clock);
        first.save("user-a", "text-model", objectMapper.createObjectNode().put("model", "text-model"), 2, false);

        CheckpointStore second = new CheckpointStore(objectMapper, Path.of(file), clock);
        CheckpointStore.TaskCheckpoint loaded = second.load("user-a").orElseThrow();
        assertEquals("text-model", loaded.model());
        assertEquals(2, loaded.round());
        assertEquals("text-model", loaded.payload().path("model").asText());
        assertFalse(loaded.withImages());
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
