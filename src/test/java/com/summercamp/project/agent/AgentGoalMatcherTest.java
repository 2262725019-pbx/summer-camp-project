package com.summercamp.project.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class AgentGoalMatcherTest {
    private final AgentGoalMatcher matcher = new AgentGoalMatcher();

    @Test
    void matchesExplicitAgentCommandAndReturnsActualGoal() {
        AgentGoalMatch result = matcher.parse(
                "  /AgEnT   帮我制定未来7天的运动、饮食和作息规划  ");

        assertEquals(AgentGoalMatch.Status.MATCHED, result.status());
        assertEquals("帮我制定未来7天的运动、饮食和作息规划", result.goal());
        assertEquals(result.goal(), matcher.match(
                "/agent 帮我制定未来7天的运动、饮食和作息规划").orElseThrow());
    }

    @Test
    void distinguishesEmptyExplicitGoalFromOrdinaryMiss() {
        assertEquals(AgentGoalMatch.Status.EMPTY_GOAL, matcher.parse("/agent    ").status());
        assertEquals(AgentGoalMatch.Status.NOT_MATCHED, matcher.parse("你好").status());
    }

    @Test
    void matchesOnlyCompositeLongTermNaturalHealthGoals() {
        List<String> goals = List.of(
                "帮我制定未来七天的运动、饮食和作息综合计划",
                "规划本周健身、营养和睡眠的完整健康生活方案",
                "安排每天跑步、餐食和早睡的健康生活计划",
                "制定未来三天结合天气与户外训练的综合方案"
        );

        assertTrue(goals.stream().allMatch(goal -> matcher.match(goal).isPresent()));
    }

    @Test
    void keepsExistingSingleCapabilityAndOrdinaryRoutesOutOfAgent() {
        List<String> nonAgentMessages = List.of(
                "帮我制定增肌饮食计划",
                "帮我制定运动计划",
                "未来三天镇江天气",
                "计算125乘36",
                "JSON格式化：{\"a\":1}",
                "你好",
                "查天气后给我运动建议",
                "帮我安排今天的运动和饮食",
                "/clear",
                "/help",
                "/image 月球上的橘猫",
                "不要制定未来七天运动饮食计划"
        );

        assertTrue(nonAgentMessages.stream().allMatch(message -> matcher.match(message).isEmpty()));
    }
}
