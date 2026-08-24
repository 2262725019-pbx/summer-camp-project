package com.summercamp.project.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class SkillRegistryTest {

    @Test
    void shouldChooseHighestScoreThenPriority() {
        BotSkill low = skill("low", 10, 5);
        BotSkill high = skill("high", 10, 20);
        SkillRegistry registry = new SkillRegistry(List.of(low, high));

        assertEquals("high", registry.match("anything").orElseThrow().skill().name());
    }

    @Test
    void shouldRejectDuplicateNames() {
        assertThrows(IllegalStateException.class, () -> new SkillRegistry(List.of(
                skill("same", 1, 0),
                skill("same", 2, 0))));
    }

    private BotSkill skill(String name, int score, int priority) {
        return new BotSkill() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public int priority() {
                return priority;
            }

            @Override
            public int matchScore(String text) {
                return score;
            }

            @Override
            public SkillResult execute(SkillContext context) {
                return SkillResult.completed(name);
            }
        };
    }
}
