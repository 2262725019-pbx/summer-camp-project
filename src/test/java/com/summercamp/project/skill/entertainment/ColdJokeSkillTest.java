package com.summercamp.project.skill.entertainment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.summercamp.project.skill.SkillContext;
import com.summercamp.project.skill.SkillResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class ColdJokeSkillTest {

    private final ColdJokeSkill skill = new ColdJokeSkill();

    @Test
    void shouldRecognizeNaturalColdJokeRequests() {
        assertTrue(skill.matchScore("给我讲个冷笑话") > 0);
        assertTrue(skill.matchScore("来一个笑话吧") > 0);
        assertEquals(0, skill.matchScore("请介绍河南师范大学"));
    }

    @Test
    void shouldRotatePlainTextJokesForSameUser() {
        SkillContext context = new SkillContext("user-a", "讲个冷笑话", List.of(), false);

        SkillResult first = skill.execute(context);
        SkillResult second = skill.execute(context);

        assertEquals(SkillResult.Status.COMPLETED, first.status());
        assertNotEquals(first.reply(), second.reply());
        assertTrue(first.reply().startsWith("给你讲一个冷笑话："));
        assertTrue(first.reply().chars().noneMatch(character -> character == '*' || character == '#'));
    }
}
