package com.summercamp.project.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentPlanJsonParserTest {
    private final AgentPlanJsonParser parser = new AgentPlanJsonParser(new ObjectMapper());

    @Test
    void parsesStrictContractAndInitializesEveryStatusToPending() {
        AgentPlan plan = parser.parse(validJson());

        assertEquals("安排健康生活", plan.goal());
        assertEquals(4, plan.steps().size());
        assertEquals(Map.of("query", "大学生健康作息"), plan.steps().get(1).inputs());
        assertTrue(plan.steps().stream().allMatch(step -> step.status() == AgentStepStatus.PENDING));
    }

    @Test
    void rejectsNonObjectRoot() {
        assertParseFailure("[]", "root must be an object");
    }

    @Test
    void rejectsUnknownRootField() {
        assertParseFailure(
                validJson().replace("\"steps\":", "\"planner\":\"model\",\"steps\":"),
                "unsupported field");
    }

    @Test
    void rejectsNonStringGoal() {
        assertParseFailure(
                validJson().replace("\"goal\":\"安排健康生活\"", "\"goal\":42"),
                "root.goal must be a non-blank string");
    }

    @Test
    void rejectsNonArraySteps() {
        assertParseFailure(
                "{\"goal\":\"安排健康生活\",\"steps\":{}}",
                "root.steps must be an array");
    }

    @Test
    void rejectsModelProvidedStatus() {
        assertParseFailure(
                validJson().replace("\"dependsOn\":[]", "\"status\":\"COMPLETED\",\"dependsOn\":[]"),
                "unsupported field");
    }

    @Test
    void rejectsMissingRequiredStepField() {
        assertParseFailure(
                validJson().replace("\"reason\":\"建立时间基准\",", ""),
                ".reason is required");
    }

    @Test
    void rejectsNonStringDependency() {
        assertParseFailure(
                validJson().replace("\"dependsOn\":[\"S3\"]", "\"dependsOn\":[3]"),
                "must be a string array");
    }

    @Test
    void rejectsMissingInputsObject() {
        assertParseFailure(
                validJson().replace(",\"inputs\":{\"timezone\":\"Asia/Shanghai\"}", ""),
                ".inputs is required");
    }

    @Test
    void rejectsNonObjectOrNonStringInputs() {
        assertParseFailure(
                validJson().replace("\"inputs\":{\"query\":\"大学生健康作息\"}", "\"inputs\":[]"),
                "string-valued object");
        assertParseFailure(
                validJson().replace("\"query\":\"大学生健康作息\"", "\"query\":42"),
                "string-valued object");
    }

    @Test
    void rejectsTrailingJsonOrExecutableText() {
        assertParseFailure(validJson() + "\n{\"run\":\"code\"}", "not valid JSON");
    }

    @Test
    void rejectsMarkdownCodeFence() {
        assertParseFailure("```json\n" + validJson() + "\n```", "not valid JSON");
    }

    private void assertParseFailure(String raw, String expectedMessage) {
        AgentPlanParseException exception = assertThrows(
                AgentPlanParseException.class,
                () -> parser.parse(raw));
        assertTrue(exception.getMessage().contains(expectedMessage), exception::getMessage);
    }

    private String validJson() {
        return """
                {
                  "goal":"安排健康生活",
                  "steps":[
                    {"id":"S1","action":"GET_DATETIME","description":"确认时间","reason":"建立时间基准","dependsOn":[],"inputs":{"timezone":"Asia/Shanghai"}},
                    {"id":"S2","action":"RETRIEVE_KNOWLEDGE","description":"获取生活知识","reason":"采用一般建议","dependsOn":[],"inputs":{"query":"大学生健康作息"}},
                    {"id":"S3","action":"RUN_EXERCISE_SKILL","description":"规划轻量运动","reason":"改善日常活动","dependsOn":["S1","S2"],"inputs":{}},
                    {"id":"S4","action":"SYNTHESIZE","description":"汇总计划","reason":"输出最终安排","dependsOn":["S3"],"inputs":{}}
                  ]
                }
                """;
    }
}
