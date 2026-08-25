package com.summercamp.project.skill.health;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summercamp.project.llm.ChatModelClient;
import com.summercamp.project.llm.ChatOutcome;
import com.summercamp.project.llm.ChatRequest;
import com.summercamp.project.llm.LlmException;
import com.summercamp.project.rag.RagContext;
import com.summercamp.project.rag.RagDocument;
import com.summercamp.project.skill.BotSkill;
import com.summercamp.project.skill.SkillContext;
import com.summercamp.project.skill.SkillResult;
import com.summercamp.project.tool.ToolContext;
import com.summercamp.project.tool.ToolRegistry;
import com.summercamp.project.tool.ToolResult;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 大学生智能健康生活 Agent。
 *
 * <p>用户只需输入一句话的最终目的（例如“帮我制定一份大学生健康生活方案”），Agent 会自主拆解为
 * 多个子任务：RAG 知识检索、调用日期时间工具、调用计算工具得出 BMI/基础代谢/热量目标，
 * 再由大模型综合产出一份完整健康生活方案成品，并通过 Function Calling 把行动项写入待办，形成闭环。
 */
@Component
public class HealthyLifestyleAgentSkill implements BotSkill {

    private static final Logger LOGGER = LoggerFactory.getLogger(HealthyLifestyleAgentSkill.class);

    public static final String SKILL_NAME = "healthy-lifestyle-agent";

    private static final String INSTRUCTIONS_RESOURCE = "skills/healthy-lifestyle-agent/SKILL.md";
    private static final String KNOWLEDGE_RESOURCE = "skills/healthy-lifestyle-agent/knowledge.json";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA);
    private static final DateTimeFormatter WEEKDAY_FMT =
            DateTimeFormatter.ofPattern("EEEE", Locale.CHINA);
    private static final int TOP_K = 3;

    private static final List<String> TRIGGER_TERMS = List.of(
            "大学生健康生活", "健康生活方案", "健康生活计划", "健康生活agent", "健康生活助手",
            "制定健康生活", "帮我制定健康", "大学生健康", "健康生活规划", "智能健康生活");

    private static final Pattern SEX = Pattern.compile("性别\\s*[：:=]?\\s*([男女])");
    private static final Pattern AGE = Pattern.compile("年龄\\s*[：:=]?\\s*(\\d{1,3})");
    private static final Pattern HEIGHT = Pattern.compile(
            "身高\\s*[：:=]?\\s*(\\d+(?:\\.\\d+)?)\\s*(?:cm|厘米)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern WEIGHT = Pattern.compile(
            "体重\\s*[：:=]?\\s*(\\d+(?:\\.\\d+)?)\\s*(?:kg|公斤|千克)?", Pattern.CASE_INSENSITIVE);

    private final ChatModelClient chatClient;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String instructions;
    private final List<RagDocument> knowledgeBase;

    @Autowired
    public HealthyLifestyleAgentSkill(
            ChatModelClient chatClient,
            ToolRegistry toolRegistry,
            ObjectMapper objectMapper) {
        this(chatClient, toolRegistry, objectMapper, Clock.systemUTC());
    }

    HealthyLifestyleAgentSkill(
            ChatModelClient chatClient,
            ToolRegistry toolRegistry,
            ObjectMapper objectMapper,
            Clock clock) {
        this.chatClient = chatClient;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.instructions = loadInstructions();
        this.knowledgeBase = loadKnowledge();
    }

    @Override
    public String name() {
        return SKILL_NAME;
    }

    @Override
    public int priority() {
        return 75;
    }

    @Override
    public int matchScore(String text) {
        String normalized = normalize(text);
        int longest = TRIGGER_TERMS.stream()
                .filter(normalized::contains)
                .mapToInt(String::length)
                .max()
                .orElse(0);
        return longest == 0 ? 0 : 75 + longest;
    }

    @Override
    public SkillResult execute(SkillContext context) {
        Profile profile = parseProfile(context.text());

        // 子任务 1：RAG 检索本地健康知识库
        RagContext rag = retrieveKnowledge(context.text());

        // 子任务 2：调用日期时间工具
        DateTimeSnapshot datetime = currentDateTime();

        // 子任务 3：调用计算工具得出 BMI，本地推算基础代谢与热量目标
        double bmi = computeBmi(profile);
        String bmiCategory = classifyBmi(bmi);
        double bmr = computeBmr(profile);
        double calorieTarget = computeCalorieTarget(profile, bmr);
        Metrics metrics = new Metrics(bmi, bmiCategory, bmr, calorieTarget);

        // 子任务 4：大模型综合产出完整方案，并可通过 Function Calling 写入待办形成闭环
        String grounding = buildGrounding(profile, rag, datetime, metrics);
        String reply;
        try {
            ChatOutcome outcome = chatClient.chat(
                    new ChatRequest(context.history(), context.text(), List.of(), grounding),
                    new ToolContext(context.userId(), context.text(), context.history()));
            reply = outcome.text().strip();
        } catch (LlmException exception) {
            LOGGER.warn("健康生活 Agent 调用大模型失败，回退本地成品：{}", exception.getMessage());
            reply = buildLocalPlan(profile, rag, datetime, metrics);
        }
        if (reply.isBlank()) {
            reply = buildLocalPlan(profile, rag, datetime, metrics);
        }
        return SkillResult.completed(reply);
    }

    private Profile parseProfile(String text) {
        Boolean maleBox = find(SEX, text).map("男"::equals).orElse(null);
        Integer ageBox = find(AGE, text).map(Integer::parseInt).orElse(null);
        Double heightBox = find(HEIGHT, text).map(Double::parseDouble).orElse(null);
        Double weightBox = find(WEIGHT, text).map(Double::parseDouble).orElse(null);
        Goal goal = detectGoal(text);

        boolean missing = maleBox == null || ageBox == null || heightBox == null || weightBox == null;
        // 缺失资料时使用大学生合理默认值并标注假设
        boolean male = maleBox == null ? true : maleBox;
        int age = ageBox == null ? 20 : clamp(ageBox, 16, 60, 20);
        double heightCm = heightBox == null ? 172.0 : heightBox;
        double weightKg = weightBox == null ? 65.0 : weightBox;
        return new Profile(male, age, heightCm, weightKg, goal, missing);
    }

    private Goal detectGoal(String text) {
        String normalized = normalize(text);
        if (normalized.contains("减脂") || normalized.contains("减肥") || normalized.contains("减重")) {
            return Goal.FAT_LOSS;
        }
        if (normalized.contains("增肌") || normalized.contains("增重") || normalized.contains("长肌肉")) {
            return Goal.MUSCLE_GAIN;
        }
        if (normalized.contains("睡眠") || normalized.contains("失眠") || normalized.contains("熬夜")) {
            return Goal.SLEEP;
        }
        if (normalized.contains("压力") || normalized.contains("减压") || normalized.contains("焦虑")) {
            return Goal.STRESS;
        }
        return Goal.GENERAL;
    }

    private RagContext retrieveKnowledge(String query) {
        String normalizedQuery = normalize(query);
        if (normalizedQuery.isBlank()) {
            return RagContext.empty();
        }
        List<RagContext.Hit> hits = knowledgeBase.stream()
                .map(document -> new RagContext.Hit(document, score(document, normalizedQuery)))
                .filter(hit -> hit.score() > 0)
                .sorted(Comparator.comparingInt(RagContext.Hit::score).reversed()
                        .thenComparing(hit -> hit.document().id()))
                .limit(TOP_K)
                .toList();
        if (hits.isEmpty()) {
            // 命中失败时回退返回最相关的通用文档，保证 Agent 有知识依据
            hits = knowledgeBase.stream()
                    .map(document -> new RagContext.Hit(document, 1))
                    .limit(TOP_K)
                    .toList();
        }
        return new RagContext(hits, buildRagContext(hits));
    }

    private int score(RagDocument document, String query) {
        int score = 0;
        String title = normalize(document.title());
        if (!title.isBlank() && query.contains(title)) {
            score += 3;
        }
        for (String rawKeyword : document.keywords()) {
            String keyword = normalize(rawKeyword);
            if (keyword.isBlank()) {
                continue;
            }
            if (query.equals(keyword)) {
                score += 3;
            } else if (query.contains(keyword)) {
                score += 2;
            }
        }
        return score;
    }

    private String buildRagContext(List<RagContext.Hit> hits) {
        StringBuilder builder = new StringBuilder();
        for (RagContext.Hit hit : hits) {
            builder.append("[资料 ").append(hit.document().id()).append("] ")
                    .append(hit.document().title())
                    .append('\n')
                    .append(hit.document().content().strip())
                    .append("\n\n");
        }
        return builder.toString().strip();
    }

    private DateTimeSnapshot currentDateTime() {
        try {
            ToolRegistry.Invocation invocation =
                    toolRegistry.invoke("get_current_datetime", "{}", ToolContext.anonymous());
            if (invocation.success() && invocation.result() instanceof ToolResult.Data data) {
                JsonNode node = data.content();
                return new DateTimeSnapshot(
                        node.path("date").asText(),
                        node.path("weekday").asText(),
                        node.path("formatted").asText(""));
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("调用日期时间工具失败，回退本地时钟：{}", exception.getMessage());
        }
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(ZONE));
        return new DateTimeSnapshot(now.toLocalDate().toString(), WEEKDAY_FMT.format(now),
                DATE_FMT.format(now) + " " + WEEKDAY_FMT.format(now));
    }

    private double computeBmi(Profile profile) {
        double heightMeters = profile.heightCm() / 100.0;
        String expression = profile.weightKg() + " / (" + heightMeters + " * " + heightMeters + ")";
        String argument = "{\"expression\":\"" + expression.replace("\"", "\\\"") + "\"}";
        try {
            ToolRegistry.Invocation invocation =
                    toolRegistry.invoke("calculate", argument, ToolContext.anonymous());
            if (invocation.success() && invocation.result() instanceof ToolResult.Data data) {
                double value = data.content().path("value").asDouble();
                if (value > 0 && Double.isFinite(value)) {
                    return value;
                }
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("调用计算工具失败，回退本地 BMI 计算：{}", exception.getMessage());
        }
        return profile.weightKg() / (heightMeters * heightMeters);
    }

    private String classifyBmi(double bmi) {
        if (bmi < 18.5) {
            return "偏瘦";
        }
        if (bmi < 24) {
            return "正常";
        }
        if (bmi < 28) {
            return "超重";
        }
        return "肥胖";
    }

    private double computeBmr(Profile profile) {
        // Mifflin-St Jeor 公式
        return 10 * profile.weightKg() + 6.25 * profile.heightCm()
                - 5 * profile.age() + (profile.male() ? 5 : -161);
    }

    private double computeCalorieTarget(Profile profile, double bmr) {
        // 大学生默认轻度活动
        double maintenance = bmr * 1.375;
        return switch (profile.goal()) {
            case FAT_LOSS -> Math.max(maintenance - 400, bmr);
            case MUSCLE_GAIN -> maintenance + 250;
            case SLEEP, STRESS, GENERAL -> maintenance;
        };
    }

    private String buildGrounding(Profile profile, RagContext rag, DateTimeSnapshot datetime, Metrics metrics) {
        StringBuilder builder = new StringBuilder(instructions).append('\n');
        builder.append("\n## Agent 已完成的子任务结果\n");
        builder.append("\n### 当前日期\n").append(datetime.formatted()).append('\n');
        builder.append("\n### 用户档案与目标\n");
        builder.append("性别：").append(profile.male() ? "男" : "女")
                .append("；年龄：").append(profile.age())
                .append("；身高：").append(oneDecimal(profile.heightCm())).append("cm")
                .append("；体重：").append(oneDecimal(profile.weightKg())).append("kg\n");
        builder.append("目标：").append(profile.goal().chineseName).append('\n');
        if (profile.assumedDefaults()) {
            builder.append("（身高/体重/年龄等信息来自默认假设，请在方案中标注并提示用户可补充真实数据以个性化）\n");
        }
        builder.append("\n### 子任务1：RAG 健康知识检索\n");
        builder.append(rag.matched() ? rag.promptContext() : "未检索到匹配资料，请依据常识生成方案。");
        builder.append("\n\n### 子任务2：日期时间工具结果\n");
        builder.append("当前 ").append(datetime.date()).append(' ').append(datetime.weekday()).append('\n');
        builder.append("\n### 子任务3：身体指标计算结果（来自计算工具）\n");
        builder.append("BMI = ").append(oneDecimal(metrics.bmi()))
                .append("（").append(metrics.bmiCategory()).append("）\n");
        builder.append("基础代谢 BMR ≈ ").append(round(metrics.bmr())).append(" kcal\n");
        builder.append("每日热量目标 ≈ ").append(round(metrics.calorieTarget())).append(" kcal\n");
        return builder.toString();
    }

    private String buildLocalPlan(Profile profile, RagContext rag, DateTimeSnapshot datetime, Metrics metrics) {
        StringBuilder builder = new StringBuilder();
        builder.append("大学生智能健康生活方案\n\n");
        builder.append("1. 健康评估\n");
        builder.append("当前 BMI 约 ").append(oneDecimal(metrics.bmi()))
                .append("（").append(metrics.bmiCategory()).append("），基础代谢约 ")
                .append(round(metrics.bmr())).append(" kcal，建议每日摄入约 ")
                .append(round(metrics.calorieTarget())).append(" kcal。\n");
        if (profile.assumedDefaults()) {
            builder.append("（部分档案使用了大学生默认值，建议补充真实身高体重以个性化方案。）\n");
        }
        builder.append("\n2. 饮食建议\n");
        builder.append("三餐规律，早餐不省；食堂优选蒸煮炖，少油炸重盐；")
                .append("蛋白质约 ").append(round(profile.weightKg() * 1.4)).append("g/天。\n");
        builder.append("\n3. 运动计划\n");
        builder.append(switch (profile.goal()) {
            case FAT_LOSS -> "每周 4~5 次有氧（慢跑/快走 30~40 分钟）+ 2 次力量训练。";
            case MUSCLE_GAIN -> "每周 4 次力量训练（推/拉/腿）+ 2 次 20 分钟有氧。";
            default -> "每周 3 次有氧 + 2 次力量训练，每次 30~45 分钟。";
        }).append('\n');
        builder.append("\n4. 作息与睡眠\n23:30 前入睡，7:00 起床；午睡 20~30 分钟。\n");
        builder.append("\n5. 心理与压力管理\n");
        builder.append("使用番茄工作法拆解任务；正念呼吸（4-7-8）缓解紧张；保持社交倾诉。\n");
        builder.append("\n6. 本周行动清单\n");
        builder.append("· 固定 23:30 入睡\n· 每周运动 3 次，每次 30 分钟\n· 每日饮水 1500ml\n");
        builder.append("\n（当前为本地回退方案，已为你列出本周关键行动；如需记录为待办请回复“记一下”。）\n");
        return builder.toString();
    }

    private String loadInstructions() {
        return loadResource(INSTRUCTIONS_RESOURCE, "健康生活 Agent 说明");
    }

    private List<RagDocument> loadKnowledge() {
        ClassPathResource resource = new ClassPathResource(KNOWLEDGE_RESOURCE);
        try (InputStream input = resource.getInputStream()) {
            return List.copyOf(objectMapper.readValue(input, new TypeReference<>() { }));
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取健康知识库：" + KNOWLEDGE_RESOURCE, exception);
        }
    }

    private String loadResource(String path, String description) {
        ClassPathResource resource = new ClassPathResource(path);
        try (InputStream input = resource.getInputStream()) {
            byte[] content = input.readAllBytes();
            return content.length == 0 ? "" : new String(content, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取" + description + "：" + path, exception);
        }
    }

    private Optional<String> find(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{S}\\s]+", "");
    }

    private int clamp(int value, int min, int max, int fallback) {
        if (value < min || value > max) {
            return fallback;
        }
        return value;
    }

    private long round(double value) {
        return Math.round(value);
    }

    private String oneDecimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    enum Goal {
        FAT_LOSS("减脂"),
        MUSCLE_GAIN("增肌"),
        SLEEP("改善睡眠"),
        STRESS("缓解压力"),
        GENERAL("综合健康改善");

        private final String chineseName;

        Goal(String chineseName) {
            this.chineseName = chineseName;
        }
    }

    record Profile(boolean male, int age, double heightCm, double weightKg, Goal goal, boolean assumedDefaults) {
    }

    record Metrics(double bmi, String bmiCategory, double bmr, double calorieTarget) {
    }

    record DateTimeSnapshot(String date, String weekday, String formatted) {
    }
}
