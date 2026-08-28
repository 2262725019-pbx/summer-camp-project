package com.summercamp.project.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PendingSkillStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldRememberAndClearPendingSkill() {
        PendingSkillStore store = new PendingSkillStore(
                Clock.fixed(Instant.parse("2026-08-24T08:00:00Z"), ZoneOffset.UTC));

        store.remember("user-a", "meal-plan");
        assertEquals("meal-plan", store.get("user-a").orElseThrow());

        store.clear("user-a");
        assertTrue(store.get("user-a").isEmpty());
    }

    @Test
    void shouldPersistPendingSkillAcrossInstances() {
        Instant now = Instant.parse("2026-08-24T08:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        String file = tempDir.resolve("pending.json").toString();

        PendingSkillStore first = new PendingSkillStore(clock, file);
        first.remember("user-a", "meal-plan");

        PendingSkillStore second = new PendingSkillStore(clock, file);
        assertEquals("meal-plan", second.get("user-a").orElseThrow());
    }
}
