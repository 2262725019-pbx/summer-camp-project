package com.summercamp.project.skill.utility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summercamp.project.skill.SkillContext;
import com.summercamp.project.skill.SkillResult;
import com.summercamp.project.tool.CalculatorTool;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuickCalculatorSkillTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final QuickCalculatorSkill skill = new QuickCalculatorSkill(
            new CalculatorTool(objectMapper), objectMapper);

    @Test
    void shouldReuseCalculatorToolForDirectCalculation() {
        SkillResult result = skill.execute(new SkillContext(
                "user-a", "帮我计算 125乘36", List.of(), false));

        assertEquals(SkillResult.Status.COMPLETED, result.status());
        assertEquals("125*36 = 4500", result.reply());
    }

    @Test
    void shouldNotInterceptMultiToolRequest() {
        assertTrue(skill.matchScore("计算125乘36") > 0);
        assertEquals(0, skill.matchScore("计算125乘36，然后把结果生成二维码"));
    }
}
