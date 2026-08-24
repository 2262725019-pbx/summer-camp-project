package com.summercamp.project.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class PendingSkillStoreTest {

    @Test
    void shouldRememberAndClearPendingSkill() {
        PendingSkillStore store = new PendingSkillStore(
                Clock.fixed(Instant.parse("2026-08-24T08:00:00Z"), ZoneOffset.UTC));

        store.remember("user-a", "meal-plan");
        assertEquals("meal-plan", store.get("user-a").orElseThrow());

        store.clear("user-a");
        assertTrue(store.get("user-a").isEmpty());
    }
}
