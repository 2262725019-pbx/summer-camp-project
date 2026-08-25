package com.summercamp.project.skill.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.summercamp.project.llm.ChatOutcome;
import com.summercamp.project.llm.ChatRequest;
import com.summercamp.project.skill.SkillContext;
import com.summercamp.project.skill.SkillResult;
import com.summercamp.project.tool.ToolContext;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ExerciseHealthAdviceSkillTest {

    @Test
    void shouldInjectInstructionsAndWaitForMissingInformation() {
        AtomicReference<ChatRequest> captured = new AtomicReference<>();
        ExerciseHealthAdviceSkill skill = new ExerciseHealthAdviceSkill((request, context) -> {
            captured.set(request);
            return ChatOutcome.text("你的主要运动目标是什么？每周可以运动几次？");
        });

        SkillResult result = skill.execute(context("帮我制定运动计划"));

        assertEquals(SkillResult.Status.WAITING_INPUT, result.status());
        assertTrue(captured.get().groundingContext().contains("运动健康建议 Skill"));
    }

    @Test
    void shouldCompleteWhenModelEmitsEndMarker() {
        ExerciseHealthAdviceSkill skill = new ExerciseHealthAdviceSkill(
                (request, context) -> ChatOutcome.text("每周训练三次，每次四十分钟。\n【会话结束】"));

        SkillResult result = skill.execute(context("目标减脂，每周三次"));

        assertEquals(SkillResult.Status.COMPLETED, result.status());
        assertFalse(result.reply().contains("会话结束"));
    }

    @Test
    void shouldRecognizeExerciseRequestsWithoutTakingDietPlan() {
        ExerciseHealthAdviceSkill skill = new ExerciseHealthAdviceSkill(
                (request, context) -> ChatOutcome.text("reply"));

        assertTrue(skill.matchScore("请帮我安排一个健身计划") > 0);
        assertEquals(0, skill.matchScore("帮我制定一个增肌饮食计划"));
    }

    private SkillContext context(String text) {
        return new SkillContext("user-a", text, List.of(), false);
    }
}
