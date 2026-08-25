package com.summercamp.project.skill.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summercamp.project.llm.ChatModelClient;
import com.summercamp.project.llm.ChatOutcome;
import com.summercamp.project.llm.ChatRequest;
import com.summercamp.project.skill.SkillContext;
import com.summercamp.project.skill.SkillResult;
import com.summercamp.project.tool.CalculatorTool;
import com.summercamp.project.tool.DateTimeTool;
import com.summercamp.project.tool.ToolContext;
import com.summercamp.project.tool.ToolRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HealthyLifestyleAgentSkillTest {

    private ObjectMapper objectMapper;
    private ToolRegistry toolRegistry;
    private FakeModel model;
    private HealthyLifestyleAgentSkill skill;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        Clock fixed = Clock.fixed(Instant.parse("2026-03-18T02:30:00Z"), ZoneOffset.UTC);
        toolRegistry = new ToolRegistry(
                List.of(
                        new CalculatorTool(objectMapper),
                        new DateTimeTool(objectMapper)),
                objectMapper);
        model = new FakeModel();
        skill = new HealthyLifestyleAgentSkill(model, toolRegistry, objectMapper, fixed);
    }

    @Test
    void shouldTriggerOnHealthLifestyleGoal() {
        assertTrue(skill.matchScore("帮我制定大学生健康生活方案") > 0);
        assertTrue(skill.matchScore("我想要一份健康生活计划") > 0);
        assertEquals(0, skill.matchScore("今天宜春天气怎么样"));
    }

    @Test
    void shouldDecomposeAndReturnCompletePlan() {
        model.reply = "大学生智能健康生活方案\n1. 健康评估\n2. 饮食建议\n3. 运动计划\n6. 本周行动清单";
        String text = "我是男生年龄20身高175cm体重70kg，帮我制定大学生健康生活方案";
        SkillResult result = skill.execute(new SkillContext("user1", text, List.of(), false));

        assertEquals(SkillResult.Status.COMPLETED, result.status());
        assertTrue(result.reply().contains("健康评估"));
        // Agent 已把 RAG、日期、计算结果写进 groundingContext 交给大模型
        assertTrue(model.lastGrounding.contains("子任务1"));
        assertTrue(model.lastGrounding.contains("子任务2"));
        assertTrue(model.lastGrounding.contains("子任务3"));
        assertTrue(model.lastGrounding.contains("BMI"));
        assertTrue(model.lastGrounding.contains("基础代谢"));
    }

    @Test
    void shouldComputeBmiViaCalculatorTool() {
        // 男生 175cm 70kg：BMI = 70/(1.75^2) ≈ 22.9（正常）
        skill.execute(new SkillContext("user1",
                "男生年龄20身高175cm体重70kg，健康生活方案", List.of(), false));
        assertTrue(model.lastGrounding.contains("BMI = 22.9"));
        assertTrue(model.lastGrounding.contains("正常"));
        assertTrue(model.lastGrounding.contains("基础代谢 BMR ≈ 1699"));
    }

    @Test
    void shouldUseDefaultsWhenProfileMissing() {
        skill.execute(new SkillContext("user1",
                "帮我制定大学生健康生活方案", List.of(), false));
        assertTrue(model.lastGrounding.contains("默认假设"));
        // 默认身高 172、体重 65 仍能算出 BMI
        assertTrue(model.lastGrounding.contains("BMI ="));
    }

    @Test
    void shouldFallBackToLocalPlanWhenModelFails() {
        model.throwLlm = true;
        SkillResult result = skill.execute(new SkillContext("user1",
                "男生年龄20身高175cm体重70kg，健康生活方案", List.of(), false));
        assertEquals(SkillResult.Status.COMPLETED, result.status());
        assertTrue(result.reply().contains("健康评估"));
        assertTrue(result.reply().contains("本周行动清单"));
        assertFalse(result.reply().isBlank());
    }

    @Test
    void shouldRetrieveRelevantKnowledgeForFatLossGoal() {
        skill.execute(new SkillContext("user1",
                "男生年龄21身高180cm体重80kg，想减脂的健康生活方案", List.of(), false));
        // 减脂目标应检索到饮食与运动相关资料
        assertTrue(model.lastGrounding.contains("饮食") || model.lastGrounding.contains("运动"));
        assertTrue(model.lastGrounding.contains("减脂"));
    }

    static class FakeModel implements ChatModelClient {
        String lastGrounding;
        String reply = "已完成方案";
        boolean throwLlm;

        @Override
        public ChatOutcome chat(ChatRequest request, ToolContext context) {
            lastGrounding = request.groundingContext();
            if (throwLlm) {
                throw new com.summercamp.project.llm.LlmException("模型不可用");
            }
            return ChatOutcome.text(reply);
        }
    }
}
