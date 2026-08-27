package com.summercamp.project.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.summercamp.project.skill.SkillContext;
import com.summercamp.project.skill.SkillResult;
import com.summercamp.project.skill.health.ExerciseHealthAdviceSkill;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class ExercisePlanTool implements BotTool {

    private final ObjectProvider<ExerciseHealthAdviceSkill> exerciseSkillProvider;
    private final ObjectMapper objectMapper;
    private final ToolDefinition definition;

    public ExercisePlanTool(ObjectProvider<ExerciseHealthAdviceSkill> exerciseSkillProvider,
                            ObjectMapper objectMapper) {
        this.exerciseSkillProvider = exerciseSkillProvider;
        this.objectMapper = objectMapper;

        ObjectNode schema = objectMapper.createObjectNode().put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("goal")
            .put("type", "string")
            .put("description", "运动目标，例如减重、增肌、提高体能等")
            .put("minLength", 1)
            .put("maxLength", 200);
        properties.putObject("city")
            .put("type", "string")
            .put("description", "所在城市（用于查询天气）")
            .put("maxLength", 100);
        properties.putObject("additionalInfo")
            .put("type", "string")
            .put("description", "其他补充信息，如每周可运动次数、喜欢的运动、身体限制等")
            .put("maxLength", 500);

        schema.putArray("required").add("goal");
        schema.put("additionalProperties", false);

        definition = new ToolDefinition(
            "generate_exercise_plan",
            "根据用户目标和城市生成运动健康建议。如果用户提供了城市，可结合天气给出调整。",
            schema);
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public boolean parallelSafe() {
        return true;
    }

    @Override
    public ToolResult execute(JsonNode arguments, ToolContext context) {
        StringBuilder userText = new StringBuilder();
        userText.append("运动目标：").append(arguments.path("goal").asText().strip()).append("\n");
        if (!arguments.path("city").asText().isBlank()) {
            userText.append("所在城市：").append(arguments.path("city").asText().strip()).append("\n");
        }
        if (!arguments.path("additionalInfo").asText().isBlank()) {
            userText.append("其他信息：").append(arguments.path("additionalInfo").asText().strip()).append("\n");
        }

        SkillContext skillContext = new SkillContext(context.userId(), userText.toString(), context.history(), false);
        ExerciseHealthAdviceSkill skill = exerciseSkillProvider.getIfAvailable();
        if (skill == null) {
            return ToolResult.text("运动健康建议服务暂不可用，请稍后再试。");
        }

        SkillResult result = skill.execute(skillContext);
        if (result.status() == SkillResult.Status.WAITING_INPUT) {
            return ToolResult.text("运动计划信息不完整，请补充：" + result.reply());
        }
        return ToolResult.text(result.reply());
    }
}
