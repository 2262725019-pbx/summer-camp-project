package com.summercamp.project.skill.health;

import com.summercamp.project.llm.ChatModelClient;
import com.summercamp.project.llm.ChatOutcome;
import com.summercamp.project.llm.ChatRequest;
import com.summercamp.project.rag.RagRetriever;
import com.summercamp.project.skill.BotSkill;
import com.summercamp.project.skill.SkillContext;
import com.summercamp.project.skill.SkillResult;
import com.summercamp.project.skill.health.HealthPlanCalculator.Meal;
import com.summercamp.project.skill.health.HealthPlanCalculator.MealPlan;
import com.summercamp.project.skill.health.HealthPlanCalculator.Metrics;
import com.summercamp.project.skill.health.HealthPlanCalculator.Portion;
import com.summercamp.project.skill.health.HealthProfileParser.ParseResult;
import com.summercamp.project.skill.health.HealthProfileParser.Profile;
import com.summercamp.project.skill.nutrition.FoodCatalog;
import com.summercamp.project.schedule.ReminderSubscriptionManager;
import com.summercamp.project.tool.TodoService;
import com.summercamp.project.tool.ToolContext;
import com.summercamp.project.weather.WeatherClient;
import com.summercamp.project.weather.WeatherPeriod;
import com.summercamp.project.weather.WeatherReport;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 大学生智能健康生活规划 Agent。
 * 用户只提供一句话最终目标，本 Skill 自主拆解为目标解析、指标计算、饮食、
 * 运动、作息与闭环交付等子任务，最终输出一份完整的健康生活规划书。
 */
@Component
public class HealthLifePlanSkill implements BotSkill {

    public static final String SKILL_NAME = "health-life-plan";

    private static final Logger LOGGER = LoggerFactory.getLogger(HealthLifePlanSkill.class);
    private static final String INSTRUCTIONS_RESOURCE = "skills/health-life-plan/SKILL.md";
    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String FOLLOW_UP_TEMPLATE = """
            我可以为你生成一份完整的健康生活规划。为了准确计算热量和营养，请补充以下资料（一条消息内发送即可）：
            性别：男
            年龄：20
            身高：175cm
            体重：70kg

            也可顺带说明：目标周期（如“一个月”）、所在城市、喜欢的运动、每周训练次数（如“每周3次”）、每天几餐。
            若不方便提供，回复“直接生成”，我会使用大学生常见默认值估算。
            """;
    private static final String SAFETY_NOTICE = "本计划为一般性估算，不替代医疗或个体化营养建议。";
    private static final int MAX_PLAN_ATTEMPTS = 2;
    private static final int MIN_PLAN_CHARACTERS = 150;
    /** 追问标记的存活时长，与路由层 PendingSkillStore 的 5 分钟续接窗口对齐。 */
    private static final Duration ASK_RETRY_TTL = Duration.ofMinutes(5);
    /** 模型反问用户资料的特征，说明它没有按指令直接生成。 */
    private static final List<String> ASKING_FEATURES = List.of(
            "你的性别", "你的年龄", "请告诉我你的", "先确认一个信息", "能否提供", "请问你的",
            "你目前的身高", "你目前体重", "告诉我你的");
    /** 完整规划书必须包含的五个章节特征。 */
    private static final List<String> REQUIRED_SECTIONS = List.of(
            "目标与身体指标", "参考餐单", "运动方案", "作息", "安全提醒");
    private static final String FALLBACK_EXERCISE = """
            每周安排：3 次有氧（每次 30～40 分钟）和 2 次力量训练（每次 40～60 分钟），训练日之间安排休息日。
            每次训练：热身 5～10 分钟 → 主体训练 → 拉伸放松 5～10 分钟。
            要点：初次锻炼从低强度开始，循序渐进；出现胸痛、晕厥或严重呼吸困难时立即停止运动并咨询医生。
            """;
    private static final String FALLBACK_ROUTINE = """
            睡眠：每晚 7～9 小时，固定就寝与起床时间，睡前减少手机蓝光。
            饮水：每日 1500～2500 毫升，少量多次。
            久坐：每 45～60 分钟起身活动 3～5 分钟。
            执行：每周固定时间记录体重与围度，观察 2～3 周趋势后小幅调整；用打卡清单降低执行难度，并安排休息日防止过度疲劳。
            """;

    private static final List<String> TRIGGER_TERMS = List.of(
            "健康规划", "健康计划", "健康生活", "生活规划", "生活计划", "整体方案", "健康管理",
            "减脂计划", "减肥计划", "瘦身计划", "减重计划", "健康目标", "作息计划", "调理计划",
            "改善体质", "提升健康", "全面健康", "健康方案");
    /** 口语化目标，如“减 8 斤”“增重 5 公斤”，避免被意图层当作天气/计算拦截。 */
    private static final Pattern WEIGHT_CHANGE_GOAL = Pattern.compile(
            "(?:减|增)\\s*\\d+(?:\\.\\d+)?\\s*(?:斤|公斤|kg|千克)");
    /** 身体数据，如“175cm”“70kg”，用于配合目标触发，降低误伤。 */
    private static final Pattern BODY_MEASURE = Pattern.compile(
            "\\d{2,3}\\s*(?:cm|厘米)|\\d+(?:\\.\\d+)?\\s*(?:kg|公斤|千克)");
    private static final List<String> UNSUPPORTED_HEALTH_TERMS = List.of(
            "未成年", "孕妇", "怀孕", "肾病", "肾脏", "肝病", "肝脏", "糖尿病", "代谢疾病",
            "进食障碍", "食物过敏", "有过敏");

    private final ChatModelClient chatClient;
    private final RagRetriever ragRetriever;
    private final FoodCatalog foods;
    private final TodoService todoService;
    private final WeatherClient weatherClient;
    private final ReminderSubscriptionManager subscriptions;
    /** 每个用户最近一次追问的时间，TTL 内不重复追问；到期后允许重新补充资料。 */
    private final Map<String, Instant> askedAt = new ConcurrentHashMap<>();
    private final String instructions;

    public HealthLifePlanSkill(
            ChatModelClient chatClient,
            RagRetriever ragRetriever,
            FoodCatalog foods,
            TodoService todoService,
            WeatherClient weatherClient,
            ReminderSubscriptionManager subscriptions) {
        this.chatClient = chatClient;
        this.ragRetriever = ragRetriever;
        this.foods = foods;
        this.todoService = todoService;
        this.weatherClient = weatherClient;
        this.subscriptions = subscriptions;
        this.instructions = loadInstructions();
    }

    @Override
    public String name() {
        return SKILL_NAME;
    }

    @Override
    public int priority() {
        return 50;
    }

    @Override
    public int matchScore(String text) {
        String normalized = normalize(text);
        int longest = TRIGGER_TERMS.stream()
                .filter(normalized::contains)
                .mapToInt(String::length)
                .max()
                .orElse(0);
        if (longest > 0) {
            return 50 + longest;
        }
        // 一句话目标：体重变化 + 身体数据同时出现（如“想一个月减 8 斤，175cm 70kg”）。
        if (WEIGHT_CHANGE_GOAL.matcher(text).find() && BODY_MEASURE.matcher(text).find()) {
            return 60;
        }
        return 0;
    }

    @Override
    public SkillResult execute(SkillContext context) {
        String text = context.text().strip();
        if (hasUnsupportedHealthCondition(text)) {
            return SkillResult.completed("""
                    为了安全，健康生活规划只面向无食物过敏的健康成年人。如果你未满 18 岁、处于孕期，
                    或存在肾脏、肝脏、代谢、进食障碍及食物过敏等情况，请先咨询医生或注册营养师。
                    """);
        }

        ParseResult parsed = HealthProfileParser.parse(text);
        if (!parsed.missingCritical().isEmpty() && shouldAskOnce(context.userId())) {
            return SkillResult.waitingInput(FOLLOW_UP_TEMPLATE);
        }

        Profile profile = fillDefaults(parsed.profile());
        Metrics metrics = HealthPlanCalculator.calculate(profile, LocalDate.now(CHINA_ZONE));
        MealPlan mealPlan = HealthPlanCalculator.buildMealPlan(profile, metrics, foods);

        List<String> assumed = assumedFields(parsed.profile());
        String facts = summarize(profile, metrics, mealPlan);
        String weather = queryWeather(profile.city());
        if (!weather.isBlank()) {
            facts += "\n\n【近期天气】\n" + weather;
        }
        String ragContext = ragRetriever.retrieve(text).promptContext();
        String plan = generatePlan(context, facts, ragContext, metrics, profile, mealPlan, assumed);

        int addedTodos = addActionItems(context.userId(), metrics);
        if (addedTodos > 0) {
            plan += "\n\n已将 " + addedTodos + " 条关键行动加入你的待办（可用“查看我的待办”查看）。";
        }
        subscriptions.subscribeHealth(
                context.userId(),
                profile.goal().chineseName(),
                Math.toIntExact(metrics.caloriesRounded()));
        StringBuilder notice = new StringBuilder("\n\n已为你开启健康提醒订阅（每天 21:00）");
        if (profile.city() != null) {
            subscriptions.subscribeWeather(context.userId(), profile.city());
            notice.append("，以及 ").append(profile.city()).append(" 天气播报（每天 07:30）");
        }
        notice.append("。发送“退订提醒”或“退订天气”可取消。");
        return SkillResult.completed(plan + notice);
    }

    /**
     * 由大模型基于系统给定的全部计算数据生成完整规划书；
     * 输出不达标或模型失败时，降级为本地确定性拼装，保证成品闭环。
     */
    private String generatePlan(
            SkillContext context,
            String facts,
            String ragContext,
            Metrics metrics,
            Profile profile,
            MealPlan mealPlan,
            List<String> assumed) {
        String grounding = "【你的角色与指令】\n" + instructions
                + "\n\n【系统提供的全部数据】\n" + facts
                + (ragContext.isBlank() ? "" : "\n\n【健康知识参考】\n" + ragContext);
        for (int attempt = 1; attempt <= MAX_PLAN_ATTEMPTS; attempt++) {
            try {
                ChatOutcome outcome = chatClient.chat(
                        new ChatRequest(context.history(), context.text(), List.of(), grounding),
                        new ToolContext(context.userId(), context.text(), context.history()));
                String plan = outcome.text().strip();
                if (isAcceptablePlan(plan, metrics)) {
                    return plan;
                }
                LOGGER.warn("健康规划书输出不符合要求，第 {} 次尝试无效：{}", attempt, preview(plan));
            } catch (RuntimeException exception) {
                LOGGER.warn("健康规划书生成失败（第 {} 次）：{}", attempt, exception.getMessage());
            }
        }
        return buildLocalPlan(profile, metrics, mealPlan, assumed);
    }

    /**
     * 拒绝模型反问用户资料、遗漏章节或关键数值的输出。
     * 关键数值必须原样出现在规划书中，防止模型编造或遗漏。
     */
    private boolean isAcceptablePlan(String plan, Metrics metrics) {
        if (plan == null || plan.isBlank() || plan.length() < MIN_PLAN_CHARACTERS) {
            return false;
        }
        String normalized = normalize(plan);
        for (String asking : ASKING_FEATURES) {
            if (normalized.contains(asking)) {
                return false;
            }
        }
        for (String section : REQUIRED_SECTIONS) {
            if (!normalized.contains(section)) {
                return false;
            }
        }
        if (!plan.contains(String.valueOf(metrics.caloriesRounded()))
                || !plan.contains(String.valueOf(metrics.proteinRounded()))) {
            return false;
        }
        return normalized.contains("不替代医疗");
    }

    private String preview(String section) {
        if (section == null || section.isBlank()) {
            return "<空>";
        }
        String compact = section.replaceAll("\\s+", " ").strip();
        return compact.length() <= 80 ? compact : compact.substring(0, 80) + "…";
    }

    private String summarize(Profile profile, Metrics metrics, MealPlan mealPlan) {
        StringBuilder builder = new StringBuilder();
        builder.append("用户目标：").append(profile.goal().chineseName()).append('\n');
        builder.append("性别：").append(Boolean.TRUE.equals(profile.male()) ? "男" : "女").append('\n');
        builder.append("年龄：").append(profile.age()).append(" 岁\n");
        builder.append("身高：").append(oneDecimal(profile.heightCm())).append(" cm\n");
        builder.append("体重：").append(oneDecimal(profile.weightKg())).append(" kg\n");
        builder.append("目标周期：").append(profile.periodDays()).append(" 天\n");
        builder.append("达成日：").append(profile.periodDays()).append(" 天后").append('\n');
        if (profile.city() != null) {
            builder.append("所在城市：").append(profile.city()).append('\n');
        }
        if (profile.trainingPreference() != null) {
            builder.append("训练偏好：").append(profile.trainingPreference()).append('\n');
        }
        builder.append("BMI：").append(oneDecimal(metrics.bmi())).append('\n');
        builder.append("基础代谢：约 ").append(Math.round(metrics.bmr())).append(" 千卡/天\n");
        builder.append("每日消耗估算：").append(Math.round(metrics.tdee())).append(" 千卡\n");
        if (profile.weeklyTraining() != null && profile.weeklyTraining() > 0) {
            builder.append("每周训练：").append(profile.weeklyTraining()).append(" 次（用于估算每日消耗）\n");
        }
        builder.append("建议摄入热量：").append(metrics.caloriesRounded()).append(" 千卡\n");
        builder.append("营养目标：蛋白质 ").append(metrics.proteinRounded())
                .append("g、碳水 ").append(metrics.carbsRounded())
                .append("g、脂肪 ").append(metrics.fatRounded()).append('g').append('\n');
        builder.append("参考餐单：\n");
        for (Meal meal : mealPlan.meals()) {
            builder.append(meal.name()).append("：");
            for (int index = 0; index < meal.portions().size(); index++) {
                Portion portion = meal.portions().get(index);
                if (index > 0) {
                    builder.append("、");
                }
                builder.append(portion.foodName()).append(' ').append(Math.round(portion.grams())).append('g');
            }
            builder.append('\n');
        }
        builder.append("餐单合计：约 ").append(Math.round(mealPlan.totals().calories())).append(" 千卡，蛋白质 ")
                .append(oneDecimal(mealPlan.totals().protein())).append("g、碳水 ")
                .append(oneDecimal(mealPlan.totals().carbs())).append("g、脂肪 ")
                .append(oneDecimal(mealPlan.totals().fat())).append('g');
        return builder.toString();
    }

    /**
     * TTL 窗口内每个用户最多追问一次；到期后允许再次追问（与 pending 续接窗口一致），
     * 避免"已问过"被永久记住导致用户想补充资料时被静默使用默认值。
     */
    private boolean shouldAskOnce(String userId) {
        if (userId == null || userId.isBlank()) {
            return false;
        }
        Instant now = Instant.now();
        askedAt.entrySet().removeIf(entry -> entry.getValue().plus(ASK_RETRY_TTL).isBefore(now));
        if (askedAt.containsKey(userId)) {
            return false;
        }
        askedAt.put(userId, now);
        return true;
    }

    /** Java 层预查城市近期天气并拼成文本；失败不阻断主流程，由模型按数据实写或省略。 */
    private String queryWeather(String city) {
        if (city == null || city.isBlank()) {
            return "";
        }
        try {
            WeatherReport report = weatherClient.query(city, WeatherPeriod.THREE_DAYS);
            return report.formatChinese();
        } catch (RuntimeException exception) {
            LOGGER.warn("健康规划天气查询失败（{}）：{}", city, exception.getMessage());
            return "";
        }
    }

    /** 本地确定性兜底：即使大模型完全不可用，也输出完整五章规划书。 */
    private String buildLocalPlan(
            Profile profile,
            Metrics metrics,
            MealPlan mealPlan,
            List<String> assumed) {
        StringBuilder reply = new StringBuilder();
        reply.append("【健康生活规划】").append(profile.goal().chineseName())
                .append(" · ").append(profile.periodDays()).append(" 天\n\n");
        reply.append("一、目标与身体指标\n");
        reply.append("目标周期 ").append(profile.periodDays()).append(" 天，预计达成日 ")
                .append(metrics.targetDate()).append('\n');
        reply.append("BMI ").append(oneDecimal(metrics.bmi())).append("（").append(bmiCategory(metrics.bmi()))
                .append("）\n");
        reply.append("每日消耗估算 ").append(Math.round(metrics.tdee())).append(" 千卡，建议摄入 ")
                .append(metrics.caloriesRounded()).append(" 千卡\n");
        reply.append("营养目标：蛋白质 ").append(metrics.proteinRounded()).append("g、碳水 ")
                .append(metrics.carbsRounded()).append("g、脂肪 ").append(metrics.fatRounded()).append("g\n");
        if (!assumed.isEmpty()) {
            reply.append("（").append(String.join("、", assumed))
                    .append("为估算默认值，提供真实数据后可重新生成）\n");
        }

        reply.append("\n二、参考餐单（示例，可按食堂替换同类食物）\n");
        for (Meal meal : mealPlan.meals()) {
            reply.append(meal.name()).append("：");
            for (int index = 0; index < meal.portions().size(); index++) {
                Portion portion = meal.portions().get(index);
                if (index > 0) {
                    reply.append("、");
                }
                reply.append(portion.foodName()).append(' ').append(Math.round(portion.grams())).append('g');
            }
            reply.append('\n');
        }
        reply.append("合计约 ").append(Math.round(mealPlan.totals().calories())).append(" 千卡，蛋白质 ")
                .append(oneDecimal(mealPlan.totals().protein())).append("g、碳水 ")
                .append(oneDecimal(mealPlan.totals().carbs())).append("g、脂肪 ")
                .append(oneDecimal(mealPlan.totals().fat())).append("g\n");

        reply.append("\n三、运动方案\n").append(FALLBACK_EXERCISE).append('\n');
        reply.append("\n四、作息与执行\n").append(FALLBACK_ROUTINE).append('\n');
        reply.append("\n五、安全提醒\n").append(SAFETY_NOTICE);
        return reply.toString();
    }

    private int addActionItems(String userId, Metrics metrics) {
        if (userId == null || userId.isBlank()) {
            return 0;
        }
        List<String> items = List.of(
                "每天记录饮食热量并控制在 " + metrics.caloriesRounded() + " 千卡左右",
                "每周完成 3 次有氧和 2 次力量训练",
                "每周固定时间称重并记录打卡");
        int added = 0;
        for (String item : items) {
            try {
                todoService.add(userId, item);
                added++;
            } catch (RuntimeException exception) {
                LOGGER.warn("健康规划待办写入失败：{}", exception.getMessage());
            }
        }
        return added;
    }

    private Profile fillDefaults(Profile raw) {
        return new Profile(
                raw.goal(),
                raw.male() == null || raw.male(),
                raw.age() == null ? 20 : raw.age(),
                raw.heightCm() == null ? 170 : raw.heightCm(),
                raw.weightKg() == null ? 60 : raw.weightKg(),
                raw.periodDays() == null ? 30 : raw.periodDays(),
                raw.weightDeltaKg(),
                raw.city(),
                raw.trainingPreference(),
                raw.mealsPerDay() == null ? 4 : raw.mealsPerDay(),
                raw.weeklyTraining() == null ? 0 : raw.weeklyTraining());
    }

    private List<String> assumedFields(Profile raw) {
        List<String> assumed = new ArrayList<>();
        if (raw.male() == null) {
            assumed.add("性别");
        }
        if (raw.heightCm() == null) {
            assumed.add("身高");
        }
        if (raw.weightKg() == null) {
            assumed.add("体重");
        }
        if (raw.age() == null) {
            assumed.add("年龄");
        }
        if (raw.mealsPerDay() == null) {
            assumed.add("餐数");
        }
        if (raw.weeklyTraining() == null) {
            assumed.add("每周训练次数");
        }
        return assumed;
    }

    private boolean hasUnsupportedHealthCondition(String text) {
        String normalized = normalize(text);
        if (normalized.contains("无食物过敏") || normalized.contains("无过敏")) {
            normalized = normalized.replace("无食物过敏", "").replace("无过敏", "");
        }
        return UNSUPPORTED_HEALTH_TERMS.stream().anyMatch(normalized::contains);
    }

    private String bmiCategory(double bmi) {
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

    private String oneDecimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private String loadInstructions() {
        ClassPathResource resource = new ClassPathResource(INSTRUCTIONS_RESOURCE);
        try (InputStream input = resource.getInputStream()) {
            byte[] content = input.readAllBytes();
            return content.length == 0 ? "" : new String(content, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取健康生活规划 Skill 说明：" + INSTRUCTIONS_RESOURCE, exception);
        }
    }

    private String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{S}\\s]+", "");
    }
}
