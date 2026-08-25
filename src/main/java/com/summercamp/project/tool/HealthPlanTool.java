package com.summercamp.project.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.summercamp.project.rag.RagContext;
import com.summercamp.project.rag.RagRetriever;
import com.summercamp.project.weather.WeatherClient;
import com.summercamp.project.weather.WeatherPeriod;
import com.summercamp.project.weather.WeatherReport;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 大学生智能健康生活规划工具。
 * 该工具由大模型在用户提出健康规划需求时调用，内部自动执行多个子任务，
 * 最终返回一份完整的生活规划报告。
 */
@Component
public class HealthPlanTool implements BotTool {

    private static final int MAX_GOAL_LENGTH = 500;
    private static final int MAX_HEIGHT = 250;
    private static final int MAX_WEIGHT = 300;
    private static final int MAX_AGE = 100;
    private static final int MAX_SESSIONS = 7;
    private static final int MAX_MINUTES = 300;

    private final WeatherClient weatherClient;
    private final CalculatorTool calculatorTool;
    private final RagRetriever ragRetriever;
    private final ObjectMapper objectMapper;
    private final ToolDefinition definition;

    public HealthPlanTool(WeatherClient weatherClient,
                          CalculatorTool calculatorTool,
                          RagRetriever ragRetriever,
                          ObjectMapper objectMapper) {
        this.weatherClient = weatherClient;
        this.calculatorTool = calculatorTool;
        this.ragRetriever = ragRetriever;
        this.objectMapper = objectMapper;

        // 定义工具参数 Schema
        ObjectNode schema = objectMapper.createObjectNode().put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("goal")
            .put("type", "string")
            .put("description", "用户的健康目标，例如：减重5斤、增肌、改善睡眠等")
            .put("minLength", 1)
            .put("maxLength", MAX_GOAL_LENGTH);
        properties.putObject("heightCm")
            .put("type", "number")
            .put("description", "身高（厘米）")
            .put("minimum", 100)
            .put("maximum", MAX_HEIGHT);
        properties.putObject("weightKg")
            .put("type", "number")
            .put("description", "体重（公斤）")
            .put("minimum", 30)
            .put("maximum", MAX_WEIGHT);
        properties.putObject("age")
            .put("type", "integer")
            .put("description", "年龄")
            .put("minimum", 15)
            .put("maximum", MAX_AGE);
        properties.putObject("sex")
            .put("type", "string")
            .put("description", "性别，male 或 female")
            .putArray("enum").add("male").add("female");
        properties.putObject("weeklyExerciseSessions")
            .put("type", "integer")
            .put("description", "每周可锻炼次数")
            .put("minimum", 0)
            .put("maximum", MAX_SESSIONS);
        properties.putObject("exerciseMinutesPerSession")
            .put("type", "integer")
            .put("description", "每次锻炼时长（分钟）")
            .put("minimum", 0)
            .put("maximum", MAX_MINUTES);
        properties.putObject("location")
            .put("type", "string")
            .put("description", "所在城市或区县，用于查询天气")
            .put("maxLength", 100);

        schema.putArray("required").add("goal");
        schema.put("additionalProperties", false);

        this.definition = new ToolDefinition(
            "create_health_plan",
            "为大学生生成一份完整的智能健康生活规划，包含饮食、运动、作息等建议。"
                + "当用户提出健康目标（如减重、增肌、改善作息）且需要一份可执行的综合计划时使用。",
            schema);
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public boolean parallelSafe() {
        return false; // 涉及多个子任务，不允许并行
    }

    @Override
    public ToolResult execute(JsonNode arguments, ToolContext context) {
        // 1. 提取并校验参数
        String goal = arguments.path("goal").asText().strip();
        if (goal.isBlank()) {
            throw new ToolExecutionException("健康目标不能为空");
        }

        double heightCm = arguments.path("heightCm").asDouble(-1);
        double weightKg = arguments.path("weightKg").asDouble(-1);
        int age = arguments.path("age").asInt(-1);
        String sex = arguments.path("sex").asText("").strip();
        int weeklySessions = arguments.path("weeklyExerciseSessions").asInt(-1);
        int minutesPerSession = arguments.path("exerciseMinutesPerSession").asInt(-1);
        String location = arguments.path("location").asText("").strip();

        // 2. 子任务1：计算 BMI（若提供了身高体重）
        double bmi = -1;
        String bmiCategory = "";
        if (heightCm > 0 && weightKg > 0) {
            double heightM = heightCm / 100.0;
            bmi = weightKg / (heightM * heightM);
            bmi = Math.round(bmi * 10) / 10.0;
            bmiCategory = categorizeBmi(bmi);
        }

        // 3. 子任务2：查询天气（若提供了地点）
        WeatherReport weatherReport = null;
        if (!location.isBlank()) {
            try {
                weatherReport = weatherClient.query(location, WeatherPeriod.THREE_DAYS);
            } catch (Exception e) {
                // 天气查询失败不阻断主流程
                weatherReport = null;
            }
        }

        // 4. 子任务3：RAG 检索健康知识
        RagContext ragContext = ragRetriever.retrieve("健康生活 大学生 " + goal);
        String ragKnowledge = ragContext.matched() ? ragContext.promptContext() : "";

        // 5. 子任务4：组合所有信息，生成最终规划报告
        String plan = buildPlan(goal, bmi, bmiCategory, weatherReport, ragKnowledge,
            age, sex, weeklySessions, minutesPerSession);

        // 6. 返回结果
        ObjectNode result = objectMapper.createObjectNode();
        result.put("plan", plan);
        result.put("bmi", bmi);
        result.put("weather_included", weatherReport != null);
        result.put("rag_included", !ragKnowledge.isBlank());
        return ToolResult.data(result);
    }

    private String categorizeBmi(double bmi) {
        if (bmi < 18.5) return "偏瘦";
        if (bmi < 24) return "正常";
        if (bmi < 28) return "超重";
        return "肥胖";
    }

    private String buildPlan(String goal,
                             double bmi,
                             String bmiCategory,
                             WeatherReport weatherReport,
                             String ragKnowledge,
                             int age,
                             String sex,
                             int weeklySessions,
                             int minutesPerSession) {
        StringBuilder sb = new StringBuilder();
        sb.append("### 你的健康生活规划\n\n");
        sb.append("**目标**：").append(goal).append("\n\n");

        // 基本信息
        sb.append("**基本信息**\n");
        if (bmi > 0) {
            sb.append("- BMI：").append(bmi).append("（").append(bmiCategory).append("）\n");
        }
        if (age > 0) sb.append("- 年龄：").append(age).append("\n");
        if (!sex.isBlank()) sb.append("- 性别：").append(sex.equals("male") ? "男" : "女").append("\n");
        if (weeklySessions >= 0) sb.append("- 每周可锻炼：").append(weeklySessions).append("次\n");
        if (minutesPerSession > 0) sb.append("- 每次锻炼时长：").append(minutesPerSession).append("分钟\n");
        sb.append("\n");

        // 饮食建议
        sb.append("**饮食建议**\n");
        if (bmi > 0) {
            if (bmi < 18.5) {
                sb.append("- 当前偏瘦，建议增加热量摄入，每日多吃 300~500 kcal，以蛋白质和优质碳水为主。\n");
            } else if (bmi < 24) {
                sb.append("- 体重正常，保持均衡饮食，注意蛋白质、蔬菜和全谷物的搭配。\n");
            } else {
                sb.append("- 建议适当控制热量，减少高油高糖食物，增加蔬菜和膳食纤维。\n");
            }
        }
        sb.append("- 保证每天饮水 1.5~2 升，三餐规律，可安排 1~2 次健康加餐（如水果、酸奶）。\n");
        sb.append("- 大学生常见问题：避免熬夜后暴饮暴食，减少外卖和含糖饮料。\n\n");

        // 运动建议
        sb.append("**运动建议**\n");
        if (weatherReport != null) {
            sb.append("- 根据未来三天天气安排：\n");
            sb.append(weatherReport.formatChinese()).append("\n");
            sb.append("- 建议晴天进行户外有氧（跑步、快走），雨天改为室内力量或瑜伽。\n");
        } else {
            sb.append("- 未提供地点，无法获取天气信息；建议室内外结合，每周保持 3~5 次运动。\n");
        }
        if (weeklySessions > 0) {
            sb.append("- 每周 ").append(weeklySessions).append(" 次锻炼，每次 ").append(minutesPerSession).append(" 分钟，可安排：\n");
            sb.append("  - 2 次有氧（慢跑、游泳、跳绳）\n");
            sb.append("  - 2 次力量训练（深蹲、俯卧撑、哑铃）\n");
            sb.append("  - 1 次柔韧性或放松（瑜伽、拉伸）\n");
        } else {
            sb.append("- 建议每周至少 3 次运动，每次 30 分钟以上。\n");
        }
        sb.append("\n");

        // 作息建议
        sb.append("**作息建议**\n");
        sb.append("- 固定起床和入睡时间，尽量 23:30 前入睡，保证 7~8 小时睡眠。\n");
        sb.append("- 睡前 1 小时远离手机，可阅读或听轻音乐。\n");
        sb.append("- 中午小憩 20~30 分钟，避免过长导致昏沉。\n\n");

        // RAG 知识补充
        if (!ragKnowledge.isBlank()) {
            sb.append("**相关健康知识**\n");
            sb.append(ragKnowledge).append("\n\n");
        }

        // 执行清单
        sb.append("**本周执行清单**\n");
        sb.append("1. 根据饮食建议调整三餐，记录每日饮食。\n");
        sb.append("2. 完成本周运动计划，并记录体重/围度变化。\n");
        sb.append("3. 每天晚上 23:00 前放下手机，逐步调整作息。\n");
        sb.append("4. 每周日回顾执行情况，调整下周计划。\n");

        sb.append("\n> 本规划由智能健康 Agent 自动生成，结合了 BMI 计算、天气查询和健康知识库。如有特殊健康状况，请咨询专业医生。");
        return sb.toString();
    }
}
