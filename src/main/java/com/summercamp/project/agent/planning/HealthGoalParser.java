package com.summercamp.project.agent.planning;

import com.summercamp.project.agent.model.HealthGoal;
import com.summercamp.project.agent.model.HealthGoalType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class HealthGoalParser {

    private static final Pattern AGE = Pattern.compile("(?:年龄\\s*[：:=]?\\s*)?(\\d{1,2})\\s*岁");
    private static final Pattern HEIGHT = Pattern.compile("(?:身高\\s*[：:=]?\\s*)?(\\d{2,3}(?:\\.\\d+)?)\\s*(?:cm|厘米)", Pattern.CASE_INSENSITIVE);
    private static final Pattern WEIGHT = Pattern.compile("(?:体重\\s*[：:=]?\\s*)?(\\d{2,3}(?:\\.\\d+)?)\\s*(?:kg|公斤|千克)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRAINING_DAYS = Pattern.compile("(?:每周训练|周训练|每周锻炼|训练频率)\\s*[：:=]?\\s*(\\d)\\s*(?:次|天)");
    private static final Pattern TRAINING_MINUTES = Pattern.compile("(?:每次训练|单次训练|每次锻炼)\\s*[：:=]?\\s*(\\d{1,3})\\s*(?:分钟|min)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MEALS = Pattern.compile("(?:每天吃|每日餐数|每天餐数)\\s*[：:=]?\\s*(\\d)\\s*(?:餐|顿)");
    private static final Pattern DAYS = Pattern.compile("(?:未来|接下来)?\\s*(\\d{1,2})\\s*天");
    private static final Pattern LOCATION = Pattern.compile(
            "(?:住在|居住在|所在城市(?:是|为)?|城市(?:是|为)?|地点(?:是|为)?)\\s*[：:=]?\\s*([\\p{IsHan}]{2,18}?(?:省[\\p{IsHan}]{1,8}市|市|区|县))");
    private static final Pattern LABELED_LOCATION = Pattern.compile(
            "(?:所在城市|城市|地点)\\s*[：:=]\\s*([^，,。;；\\s]{2,20})");
    private static final Pattern RESIDENCE_LOCATION = Pattern.compile(
            "(?:住在|居住在)\\s*([\\p{IsHan}]{2,20}?)(?=[，,。;；\\s]|$)");
    private static final Pattern ACTIVITY = Pattern.compile(
            "(?:日常活动|活动量|活动水平)\\s*[：:=]?\\s*(久坐|轻度|中度|高度|重度|高强度|非常活跃)");

    private static final List<String> UNSAFE_TERMS = List.of(
            "受伤", "伤病", "膝盖疼", "关节疼", "胸痛", "晕厥", "怀孕", "孕期",
            "糖尿病", "肾病", "肝病", "心脏病", "高血压", "进食障碍", "正在服药");

    public HealthGoal parse(String text) {
        String source = text == null ? "" : text.strip();
        Integer trainingDays = integer(TRAINING_DAYS, source).orElse(null);
        String explicitActivity = find(ACTIVITY, source).orElse("");
        return new HealthGoal(
                goalType(source).orElse(null),
                integer(DAYS, source).orElseGet(() -> containsWeek(source) ? 7 : null),
                gender(source),
                integer(AGE, source).orElse(null),
                decimal(HEIGHT, source).orElse(null),
                decimal(WEIGHT, source).orElse(null),
                location(source),
                trainingDays,
                integer(TRAINING_MINUTES, source).orElse(null),
                integer(MEALS, source).orElse(null),
                explicitActivity.isBlank() ? deriveActivity(trainingDays) : normalizeActivity(explicitActivity),
                healthConfirmed(source),
                noFoodAllergies(source),
                safetyFlags(source),
                source);
    }

    private Optional<HealthGoalType> goalType(String text) {
        String normalized = normalize(text);
        if (normalized.contains("增肌") || normalized.contains("长肌肉") || normalized.contains("健身增重")) {
            return Optional.of(HealthGoalType.MUSCLE_GAIN);
        }
        if (normalized.contains("减脂") || normalized.contains("减肥") || normalized.contains("控制体重")) {
            return Optional.of(HealthGoalType.FAT_LOSS);
        }
        if (normalized.contains("体能") || normalized.contains("耐力") || normalized.contains("运动能力")) {
            return Optional.of(HealthGoalType.FITNESS);
        }
        if (normalized.contains("规律作息") || normalized.contains("健康生活") || normalized.contains("生活习惯")) {
            return Optional.of(HealthGoalType.HEALTHY_ROUTINE);
        }
        return Optional.empty();
    }

    private String gender(String text) {
        Matcher labeled = Pattern.compile("性别\\s*[：:=]?\\s*([男女])").matcher(text);
        if (labeled.find()) {
            return labeled.group(1);
        }
        if (Pattern.compile("(?:^|[，,。；;\\s])男(?:生|性)?(?:[，,。；;\\s]|$)").matcher(text).find()) {
            return "男";
        }
        if (Pattern.compile("(?:^|[，,。；;\\s])女(?:生|性)?(?:[，,。；;\\s]|$)").matcher(text).find()) {
            return "女";
        }
        return "";
    }

    private String location(String text) {
        return find(LOCATION, text)
                .or(() -> find(LABELED_LOCATION, text))
                .or(() -> find(RESIDENCE_LOCATION, text))
                .orElse("");
    }

    private Boolean healthConfirmed(String text) {
        String normalized = normalize(text);
        if (normalized.contains("身体健康") || normalized.contains("健康成人")
                || normalized.contains("健康状况良好") || normalized.contains("无基础疾病")) {
            return true;
        }
        if (!safetyFlags(text).isEmpty()) {
            return false;
        }
        return null;
    }

    private Boolean noFoodAllergies(String text) {
        String normalized = normalize(text);
        if (normalized.contains("没有食物过敏") || normalized.contains("无食物过敏")
                || normalized.contains("没有过敏") || normalized.contains("无过敏")) {
            return true;
        }
        if (normalized.contains("食物过敏") || normalized.contains("过敏食物")) {
            return false;
        }
        return null;
    }

    private List<String> safetyFlags(String text) {
        String normalized = normalize(text)
                .replace("没有受伤", "")
                .replace("无伤病", "")
                .replace("没有基础疾病", "")
                .replace("无基础疾病", "");
        for (String term : UNSAFE_TERMS) {
            normalized = normalized
                    .replace("没有" + term, "")
                    .replace("不存在" + term, "")
                    .replace("无" + term, "");
        }
        List<String> flags = new ArrayList<>();
        for (String term : UNSAFE_TERMS) {
            if (normalized.contains(term)) {
                flags.add(term);
            }
        }
        return List.copyOf(flags);
    }

    private String deriveActivity(Integer trainingDays) {
        if (trainingDays == null) {
            return "";
        }
        if (trainingDays <= 2) {
            return "轻度";
        }
        if (trainingDays <= 4) {
            return "中度";
        }
        return "高度";
    }

    private String normalizeActivity(String value) {
        return switch (value) {
            case "重度", "高强度", "非常活跃" -> "高度";
            default -> value;
        };
    }

    private boolean containsWeek(String text) {
        String normalized = normalize(text);
        return normalized.contains("一周") || normalized.contains("七天")
                || normalized.contains("七日") || normalized.contains("7天");
    }

    private Optional<String> find(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? Optional.of(matcher.group(1).strip()) : Optional.empty();
    }

    private Optional<Integer> integer(Pattern pattern, String text) {
        return find(pattern, text).map(Integer::parseInt);
    }

    private Optional<Double> decimal(Pattern pattern, String text) {
        return find(pattern, text).map(Double::parseDouble);
    }

    private String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{S}\\s]+", "");
    }
}
