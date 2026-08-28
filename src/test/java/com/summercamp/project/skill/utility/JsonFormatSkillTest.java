package com.summercamp.project.skill.utility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summercamp.project.skill.SkillContext;
import com.summercamp.project.skill.SkillResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class JsonFormatSkillTest {

    private final JsonFormatSkill skill = new JsonFormatSkill(new ObjectMapper());

    @Test
    void shouldFormatValidJson() {
        SkillResult result = skill.execute(context("JSON格式化：{\"name\":\"夏令营\",\"days\":3}"));

        assertEquals(SkillResult.Status.COMPLETED, result.status());
        assertTrue(result.reply().contains("\"name\" : \"夏令营\""));
        assertTrue(result.reply().contains("\"days\" : 3"));
    }

    @Test
    void shouldSupportASecondMessageAfterAskingForJson() {
        assertEquals(SkillResult.Status.WAITING_INPUT, skill.execute(context("JSON格式化：")).status());
        assertEquals(SkillResult.Status.COMPLETED, skill.execute(context("{\"ok\":true}")).status());
    }

    @Test
    void shouldReplyInvalidJsonWithoutEnteringWaitingState() {
        SkillResult result = skill.execute(context("JSON格式化：{\"a\":1,}"));

        // 非法 JSON 直接结束，不进入待补充状态，避免劫持后续消息
        assertEquals(SkillResult.Status.COMPLETED, result.status());
        assertTrue(result.reply().contains("JSON 格式不合法"));
    }

    private SkillContext context(String text) {
        return new SkillContext("user-a", text, List.of(), false);
    }
}
