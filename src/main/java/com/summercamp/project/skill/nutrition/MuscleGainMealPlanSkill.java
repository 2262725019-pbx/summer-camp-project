package com.summercamp.project.skill.nutrition;

import com.summercamp.project.skill.BotSkill;
import com.summercamp.project.skill.SkillContext;
import com.summercamp.project.skill.SkillResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class MuscleGainMealPlanSkill implements BotSkill {

    public static final String SKILL_NAME = "muscle-gain-meal-plan";

    private static final String INPUT_TEMPLATE = """
            请按下面格式补充完整资料，我会据此生成训练日和休息日两套计划：
            性别：男
            年龄：22
            身高：175cm
            体重：70kg
            日常活动：轻度
            每周训练：4次
            每次训练：60分钟
            每日餐数：4餐
            健康确认：健康成人、无食物过敏

            日常活动可填写：久坐、轻度、中度、高度；重度、高强度、非常活跃会按高度处理。
            当前版本仅面向无食物过敏的健康成人。
            """;

    private static final Pattern SEX = Pattern.compile("性别\\s*[：:=]?\\s*([男女])");
    private static final Pattern AGE = Pattern.compile("年龄\\s*[：:=]?\\s*(\\d{1,3})");
    private static final Pattern HEIGHT = Pattern.compile("身高\\s*[：:=]?\\s*(\\d+(?:\\.\\d+)?)\\s*(?:cm|厘米)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern WEIGHT = Pattern.compile("体重\\s*[：:=]?\\s*(\\d+(?:\\.\\d+)?)\\s*(?:kg|公斤|千克)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTIVITY = Pattern.compile(
            "(?:日常活动|活动量|活动水平)\\s*[：:=]?\\s*(久坐|轻度|中度|高度|重度|高强度|非常活跃)");
    private static final Pattern SESSIONS = Pattern.compile("(?:每周训练|周训练|训练频率)\\s*[：:=]?\\s*(\\d+)\\s*次");
    private static final Pattern MINUTES = Pattern.compile("(?:每次训练|单次训练|每次)\\s*[：:=]?\\s*(\\d+)\\s*(?:分钟|min)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MEALS = Pattern.compile("(?:每日餐数|每天餐数)\\s*[：:=]?\\s*(\\d+)\\s*餐");

    private static final List<String> GOAL_TERMS = List.of("增肌", "长肌肉", "健身增重");
    private static final List<String> PLAN_TERMS = List.of("饮食", "食谱", "餐单", "摄入计划", "营养计划");
    private static final List<String> UNSUPPORTED_HEALTH_TERMS = List.of(
            "未成年", "孕妇", "怀孕", "肾病", "肾脏", "肝病", "肝脏", "糖尿病", "代谢疾病", "进食障碍", "食物过敏", "有过敏");

    private final FoodCatalog foods;

    public MuscleGainMealPlanSkill(FoodCatalog foods) {
        this.foods = foods;
    }

    @Override
    public String name() {
        return SKILL_NAME;
    }

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public int matchScore(String text) {
        String normalized = normalize(text);
        boolean goal = GOAL_TERMS.stream().anyMatch(normalized::contains);
        boolean plan = PLAN_TERMS.stream().anyMatch(normalized::contains);
        if (goal && plan) {
            return 100 + longestMatchedTerm(normalized, GOAL_TERMS)
                    + longestMatchedTerm(normalized, PLAN_TERMS);
        }
        return normalized.contains("增肌计划") ? 90 : 0;
    }

    @Override
    public SkillResult execute(SkillContext context) {
        String text = context.text().strip();
        if (hasUnsupportedHealthCondition(text)) {
            return SkillResult.completed("""
                    为了安全，当前增肌饮食 Skill 只为无食物过敏的健康成年人提供一般性估算。
                    如果你未满 18 岁、处于孕期，或存在肾脏、肝脏、代谢、进食障碍及食物过敏等情况，请先咨询医生或注册营养师。
                    """);
        }

        ProfileParseResult parsed = parseProfile(text);
        if (!parsed.missingFields().isEmpty()) {
            String missing = String.join("、", parsed.missingFields());
            return SkillResult.waitingInput(
                    "没有识别到以下字段：" + missing + "。请保留字段名称并检查填写格式。\n\n" + INPUT_TEMPLATE);
        }
        Profile profile = parsed.profile();
        Optional<String> validation = validate(profile, text);
        if (validation.isPresent()) {
            return SkillResult.waitingInput(validation.get() + "\n\n" + INPUT_TEMPLATE);
        }
        return SkillResult.completed(createPlan(profile));
    }

    private ProfileParseResult parseProfile(String text) {
        Optional<String> sex = find(SEX, text);
        Optional<String> age = find(AGE, text);
        Optional<String> height = find(HEIGHT, text);
        Optional<String> weight = find(WEIGHT, text);
        Optional<String> activity = find(ACTIVITY, text);
        Optional<String> sessions = find(SESSIONS, text);
        Optional<String> minutes = find(MINUTES, text);
        Optional<String> meals = find(MEALS, text);
        List<String> missing = new ArrayList<>();
        addMissing(missing, sex, "性别");
        addMissing(missing, age, "年龄");
        addMissing(missing, height, "身高");
        addMissing(missing, weight, "体重");
        addMissing(missing, activity, "日常活动（久坐/轻度/中度/高度）");
        addMissing(missing, sessions, "每周训练次数");
        addMissing(missing, minutes, "每次训练时长");
        addMissing(missing, meals, "每日餐数");
        if (!missing.isEmpty()) {
            return new ProfileParseResult(null, List.copyOf(missing));
        }
        try {
            return new ProfileParseResult(new Profile(
                    "男".equals(sex.orElseThrow()),
                    Integer.parseInt(age.orElseThrow()),
                    Double.parseDouble(height.orElseThrow()),
                    Double.parseDouble(weight.orElseThrow()),
                    Activity.fromChinese(activity.orElseThrow()),
                    Integer.parseInt(sessions.orElseThrow()),
                    Integer.parseInt(minutes.orElseThrow()),
                    Integer.parseInt(meals.orElseThrow())), List.of());
        } catch (IllegalArgumentException exception) {
            return new ProfileParseResult(null, List.of("存在无法解析的数值或活动等级"));
        }
    }

    private void addMissing(List<String> missing, Optional<String> value, String fieldName) {
        if (value.isEmpty()) {
            missing.add(fieldName);
        }
    }

    private Optional<String> validate(Profile profile, String text) {
        if (profile.age() < 18 || profile.age() > 65) {
            return Optional.of("当前版本仅支持 18～65 岁的健康成人。");
        }
        if (profile.heightCm() < 130 || profile.heightCm() > 220) {
            return Optional.of("身高请填写 130～220 cm 之间的数值。");
        }
        if (profile.weightKg() < 35 || profile.weightKg() > 200) {
            return Optional.of("体重请填写 35～200 kg 之间的数值。");
        }
        if (profile.sessionsPerWeek() < 1 || profile.sessionsPerWeek() > 7) {
            return Optional.of("每周训练次数请填写 1～7 次。");
        }
        if (profile.minutesPerSession() < 20 || profile.minutesPerSession() > 180) {
            return Optional.of("每次训练时长请填写 20～180 分钟。");
        }
        if (profile.mealsPerDay() < 3 || profile.mealsPerDay() > 5) {
            return Optional.of("当前版本支持每天 3～5 餐。");
        }
        String normalized = normalize(text);
        if (!normalized.contains("健康确认")
                || !normalized.contains("健康成人")
                || !(normalized.contains("无食物过敏") || normalized.contains("无过敏"))) {
            return Optional.of("请明确填写“健康确认：健康成人、无食物过敏”。");
        }
        return Optional.empty();
    }

    private String createPlan(Profile profile) {
        TrainingTier tier = TrainingTier.from(profile);
        double bmr = 10 * profile.weightKg() + 6.25 * profile.heightCm()
                - 5 * profile.age() + (profile.male() ? 5 : -161);
        double maintenance = bmr * profile.activity().factor;
        double surplus = Math.min(maintenance * tier.surplusRate, 400);
        double weeklyAverage = maintenance + surplus;

        int trainingDays = profile.sessionsPerWeek();
        int restDays = 7 - trainingDays;
        double trainingCalories;
        double restCalories;
        if (restDays == 0) {
            trainingCalories = weeklyAverage;
            restCalories = weeklyAverage;
        } else {
            restCalories = weeklyAverage * 7 / (restDays + trainingDays * 1.10);
            trainingCalories = restCalories * 1.10;
        }

        MacroTarget trainingTarget = macroTarget(trainingCalories, profile.weightKg(), tier);
        MacroTarget restTarget = macroTarget(restCalories, profile.weightKg(), tier);
        DayPlan trainingPlan = buildDayPlan("训练日", trainingTarget, profile.mealsPerDay());
        DayPlan restPlan = buildDayPlan("休息日", restTarget, profile.mealsPerDay());

        StringBuilder reply = new StringBuilder();
        reply.append("增肌饮食计划（一般性估算）\n")
                .append("训练档次：").append(tier.chineseName)
                .append("；每周约 ").append(profile.sessionsPerWeek() * profile.minutesPerSession())
                .append(" 分钟训练\n")
                .append("估算基础代谢：").append(round(bmr)).append(" kcal；维持热量：")
                .append(round(maintenance)).append(" kcal\n")
                .append("整周平均增肌目标：").append(round(weeklyAverage)).append(" kcal/天\n\n");
        appendDay(reply, trainingPlan);
        reply.append('\n');
        appendDay(reply, restPlan);
        reply.append("\n说明：营养值来自本地常见食物数据，烹饪方式和品牌会造成差异。建议连续观察 2～3 周体重和训练表现后再小幅调整。此计划不替代医疗或个体化营养建议。");
        return reply.toString();
    }

    private MacroTarget macroTarget(double calories, double weightKg, TrainingTier tier) {
        double protein = weightKg * tier.proteinPerKg;
        double fat = calories * 0.25 / 9;
        double carbs = Math.max(0, (calories - protein * 4 - fat * 9) / 4);
        return new MacroTarget(calories, protein, carbs, fat);
    }

    private DayPlan buildDayPlan(String name, MacroTarget target, int mealCount) {
        List<Portion> portions = template(mealCount);
        Totals initial = totals(portions);
        double scale = target.calories() / initial.calories();
        for (Portion portion : portions) {
            portion.grams = roundToFive(clamp(portion.grams * scale, portion.minimum, portion.maximum));
        }

        double currentObjective = objective(totals(portions), target);
        for (int iteration = 0; iteration < 1_000; iteration++) {
            Portion bestPortion = null;
            double bestGrams = 0;
            double bestObjective = currentObjective;
            for (Portion portion : portions) {
                double original = portion.grams;
                for (double candidate : List.of(original - 5, original + 5)) {
                    if (candidate < portion.minimum || candidate > portion.maximum) {
                        continue;
                    }
                    portion.grams = candidate;
                    double candidateObjective = objective(totals(portions), target);
                    if (candidateObjective + 1e-9 < bestObjective) {
                        bestObjective = candidateObjective;
                        bestPortion = portion;
                        bestGrams = candidate;
                    }
                }
                portion.grams = original;
            }
            if (bestPortion == null) {
                break;
            }
            bestPortion.grams = bestGrams;
            currentObjective = bestObjective;
        }
        return new DayPlan(name, target, List.copyOf(portions), totals(portions));
    }

    private List<Portion> template(int meals) {
        List<Portion> portions = new ArrayList<>();
        add(portions, "早餐", "oats", 70, 30, 180);
        add(portions, "早餐", "milk", 250, 100, 600);
        add(portions, "早餐", "egg", 100, 50, 250);
        add(portions, "早餐", "banana", 100, 0, 300);

        add(portions, "午餐", "rice", 250, 100, 650);
        add(portions, "午餐", "chicken", 170, 80, 400);
        add(portions, "午餐", "broccoli", 200, 100, 400);
        add(portions, "午餐", "olive-oil", 10, 0, 35);

        if (meals >= 4) {
            add(portions, "加餐一", "yogurt", 200, 100, 500);
            add(portions, "加餐一", "whole-wheat-bread", 80, 0, 250);
            add(portions, "加餐一", "almond", 15, 0, 60);
        }
        if (meals == 5) {
            add(portions, "加餐二", "milk", 200, 100, 500);
            add(portions, "加餐二", "apple", 150, 0, 350);
            add(portions, "加餐二", "peanut-butter", 15, 0, 60);
        }

        add(portions, "晚餐", "rice", 220, 100, 650);
        add(portions, "晚餐", "lean-beef", 150, 80, 350);
        add(portions, "晚餐", "spinach", 200, 100, 400);
        add(portions, "晚餐", "olive-oil", 10, 0, 35);
        return portions;
    }

    private void add(List<Portion> portions, String meal, String foodId, double grams, double min, double max) {
        portions.add(new Portion(meal, foods.require(foodId), grams, min, max));
    }

    private Totals totals(List<Portion> portions) {
        double calories = 0;
        double protein = 0;
        double carbs = 0;
        double fat = 0;
        for (Portion portion : portions) {
            double factor = portion.grams / 100;
            calories += portion.food.calories() * factor;
            protein += portion.food.protein() * factor;
            carbs += portion.food.carbohydrates() * factor;
            fat += portion.food.fat() * factor;
        }
        return new Totals(calories, protein, carbs, fat);
    }

    private double objective(Totals actual, MacroTarget target) {
        return square((actual.calories() - target.calories()) / target.calories())
                + square((actual.protein() - target.protein()) / target.protein())
                + square((actual.carbohydrates() - target.carbohydrates()) / target.carbohydrates())
                + square((actual.fat() - target.fat()) / target.fat());
    }

    private void appendDay(StringBuilder reply, DayPlan plan) {
        MacroTarget target = plan.target();
        Totals actual = plan.actual();
        reply.append(plan.name()).append("目标：")
                .append(round(target.calories())).append(" kcal，蛋白质 ")
                .append(oneDecimal(target.protein())).append("g，碳水 ")
                .append(oneDecimal(target.carbohydrates())).append("g，脂肪 ")
                .append(oneDecimal(target.fat())).append("g\n");
        Map<String, List<Portion>> meals = new LinkedHashMap<>();
        for (Portion portion : plan.portions()) {
            if (portion.grams >= 2.5) {
                meals.computeIfAbsent(portion.meal, ignored -> new ArrayList<>()).add(portion);
            }
        }
        for (Map.Entry<String, List<Portion>> entry : meals.entrySet()) {
            reply.append(entry.getKey()).append("：");
            for (int index = 0; index < entry.getValue().size(); index++) {
                Portion portion = entry.getValue().get(index);
                if (index > 0) {
                    reply.append("，");
                }
                reply.append(portion.food.name()).append(' ')
                        .append(roundToFive(portion.grams)).append('g');
            }
            reply.append('\n');
        }
        reply.append("实际合计：").append(round(actual.calories())).append(" kcal，蛋白质 ")
                .append(oneDecimal(actual.protein())).append("g，碳水 ")
                .append(oneDecimal(actual.carbohydrates())).append("g，脂肪 ")
                .append(oneDecimal(actual.fat())).append("g")
                .append(withinTolerance(actual, target) ? "（目标误差在 10% 内）\n" : "（当前为近似组合，请按目标值微调）\n");
    }

    private boolean withinTolerance(Totals actual, MacroTarget target) {
        return relativeError(actual.calories(), target.calories()) <= 0.10
                && relativeError(actual.protein(), target.protein()) <= 0.10
                && relativeError(actual.carbohydrates(), target.carbohydrates()) <= 0.10
                && relativeError(actual.fat(), target.fat()) <= 0.10;
    }

    private boolean hasUnsupportedHealthCondition(String text) {
        String normalized = normalize(text);
        if (normalized.contains("无食物过敏") || normalized.contains("无过敏")) {
            normalized = normalized.replace("无食物过敏", "").replace("无过敏", "");
        }
        return UNSUPPORTED_HEALTH_TERMS.stream().anyMatch(normalized::contains);
    }

    private Optional<String> find(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private int longestMatchedTerm(String text, List<String> terms) {
        return terms.stream().filter(text::contains).mapToInt(String::length).max().orElse(0);
    }

    private String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private double square(double value) {
        return value * value;
    }

    private double relativeError(double actual, double expected) {
        return Math.abs(actual - expected) / expected;
    }

    private double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private long round(double value) {
        return Math.round(value);
    }

    private long roundToFive(double value) {
        return Math.round(value / 5) * 5;
    }

    private String oneDecimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    record Profile(
            boolean male,
            int age,
            double heightCm,
            double weightKg,
            Activity activity,
            int sessionsPerWeek,
            int minutesPerSession,
            int mealsPerDay) {
    }

    record ProfileParseResult(Profile profile, List<String> missingFields) {

        ProfileParseResult {
            missingFields = List.copyOf(missingFields);
            if (profile == null && missingFields.isEmpty()) {
                throw new IllegalArgumentException("解析失败时必须提供错误字段");
            }
        }
    }

    enum Activity {
        SEDENTARY("久坐", 1.2),
        LIGHT("轻度", 1.375),
        MODERATE("中度", 1.55),
        HIGH("高度", 1.725);

        private final String chineseName;
        private final double factor;

        Activity(String chineseName, double factor) {
            this.chineseName = chineseName;
            this.factor = factor;
        }

        static Activity fromChinese(String value) {
            if ("重度".equals(value) || "高强度".equals(value) || "非常活跃".equals(value)) {
                return HIGH;
            }
            for (Activity activity : values()) {
                if (activity.chineseName.equals(value)) {
                    return activity;
                }
            }
            throw new IllegalArgumentException("未知活动等级：" + value);
        }
    }

    enum TrainingTier {
        LOW("低", 0.05, 1.6),
        MEDIUM("中", 0.08, 1.8),
        HIGH("高", 0.10, 2.0);

        private final String chineseName;
        private final double surplusRate;
        private final double proteinPerKg;

        TrainingTier(String chineseName, double surplusRate, double proteinPerKg) {
            this.chineseName = chineseName;
            this.surplusRate = surplusRate;
            this.proteinPerKg = proteinPerKg;
        }

        static TrainingTier from(Profile profile) {
            int weeklyMinutes = profile.sessionsPerWeek() * profile.minutesPerSession();
            if (profile.sessionsPerWeek() >= 5 || weeklyMinutes > 300) {
                return HIGH;
            }
            if (profile.sessionsPerWeek() >= 3 || weeklyMinutes > 120) {
                return MEDIUM;
            }
            return LOW;
        }
    }

    record MacroTarget(double calories, double protein, double carbohydrates, double fat) {
    }

    record Totals(double calories, double protein, double carbohydrates, double fat) {
    }

    record DayPlan(String name, MacroTarget target, List<Portion> portions, Totals actual) {
    }

    private static final class Portion {
        private final String meal;
        private final FoodItem food;
        private final double minimum;
        private final double maximum;
        private double grams;

        private Portion(String meal, FoodItem food, double grams, double minimum, double maximum) {
            this.meal = meal;
            this.food = food;
            this.grams = grams;
            this.minimum = minimum;
            this.maximum = maximum;
        }
    }
}
