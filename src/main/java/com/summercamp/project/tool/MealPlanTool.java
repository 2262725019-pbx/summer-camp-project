package com.summercamp.project.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.summercamp.project.skill.SkillContext;
import com.summercamp.project.skill.SkillResult;
import com.summercamp.project.skill.nutrition.MuscleGainMealPlanSkill;
import org.springframework.stereotype.Component;

@Component
public class MealPlanTool implements BotTool {

    private static final int MAX_FIELD_LENGTH = 100;

    private final MuscleGainMealPlanSkill mealPlanSkill;
    private final ObjectMapper objectMapper;
    private final ToolDefinition definition;

    public MealPlanTool(MuscleGainMealPlanSkill mealPlanSkill, ObjectMapper objectMapper) {
        this.mealPlanSkill = mealPlanSkill;
        this.objectMapper = objectMapper;

        ObjectNode schema = objectMapper.createObjectNode().put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("sex").put("type", "string").put("description", "性别：男或女").putArray("enum").add("男").add("女");
        properties.putObject("age").put("type", "integer").put("description", "年龄").put("minimum", 18).put("maximum", 65);
        properties.putObject("heightCm").put("type", "number").put("description", "身高（厘米）").put("minimum", 130).put("maximum", 220);
        properties.putObject("weightKg").put("type", "number").put("description", "体重（公斤）").put("minimum", 35).put("maximum", 200);
        properties.putObject("activity").put("type", "string").put("description", "日常活动：久坐、轻度、中度、高度").putArray("enum").add("久坐").add("轻度").add("中度").add("高度");
        properties.putObject("sessionsPerWeek").put("type", "integer").put("description", "每周训练次数").put("minimum", 1).put("maximum", 7);
        properties.putObject("minutesPerSession").put("type", "integer").put("description", "每次训练时长（分钟）").put("minimum", 20).put("maximum", 180);
        properties.putObject("mealsPerDay").put("type", "integer").put("description", "每日餐数").put("minimum", 3).put("maximum", 5);

        schema.putArray("required")
            .add("sex")
            .add("age")
            .add("heightCm")
            .add("weightKg")
            .add("activity")
            .add("sessionsPerWeek")
            .add("minutesPerSession")
            .add("mealsPerDay");
        schema.put("additionalProperties", false);

        definition = new ToolDefinition(
            "generate_meal_plan",
            "根据用户身体数据和训练情况，生成增肌或健康饮食计划。需要提供全部参数。",
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
        // 构造符合 MuscleGainMealPlanSkill 解析格式的文本
        String userText = String.format("""
                        性别：%s
                        年龄：%d
                        身高：%.0fcm
                        体重：%.0fkg
                        日常活动：%s
                        每周训练：%d次
                        每次训练：%d分钟
                        每日餐数：%d餐
                        健康确认：健康成人、无食物过敏
                        """,
            arguments.path("sex").asText(),
            arguments.path("age").asInt(),
            arguments.path("heightCm").asDouble(),
            arguments.path("weightKg").asDouble(),
            arguments.path("activity").asText(),
            arguments.path("sessionsPerWeek").asInt(),
            arguments.path("minutesPerSession").asInt(),
            arguments.path("mealsPerDay").asInt());

        SkillContext skillContext = new SkillContext(context.userId(), userText, context.history(), false);
        SkillResult result = mealPlanSkill.execute(skillContext);
        if (result.status() == SkillResult.Status.WAITING_INPUT) {
            // 理论上不会发生，因为参数已齐全；但安全起见仍检查
            throw new ToolExecutionException("饮食计划生成失败，请检查输入参数：" + result.reply());
        }
        return ToolResult.text(result.reply());
    }
}
