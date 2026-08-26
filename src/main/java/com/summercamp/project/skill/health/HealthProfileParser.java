package com.summercamp.project.skill.health;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从"一句话最终目标"中提取健康规划所需的基本资料。
 * 只做本地确定性解析，不调用模型；缺失字段由调用方决定追问或使用默认值。
 */
public final class HealthProfileParser {

    private static final Pattern SEX = Pattern.compile("性别\\s*[：:=]?\\s*([男女])");
    private static final Pattern SEX_WORD = Pattern.compile("[男女]");
    private static final Pattern AGE = Pattern.compile("年龄\\s*[：:=]?\\s*(\\d{1,3})|(\\d{1,2})\\s*岁");
    private static final Pattern HEIGHT = Pattern.compile("身高\\s*[：:=]?\\s*(\\d+(?:\\.\\d+)?)|(\\d{3})\\s*(?:cm|厘米)");
    private static final Pattern HEIGHT_METERS_CN = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*米\\s*(?:(\\d{1,2}))?");
    private static final Pattern HEIGHT_METERS_LATIN = Pattern.compile("(\\d+\\.\\d+)\\s*m\\b");
    private static final Pattern WEIGHT = Pattern.compile("体重\\s*[：:=]?\\s*(\\d+(?:\\.\\d+)?)|(\\d+(?:\\.\\d+)?)\\s*(?:kg|公斤|千克)");
    private static final Pattern PERIOD_DAYS = Pattern.compile("(\\d+)\\s*天");
    private static final Pattern PERIOD_WEEKS = Pattern.compile("(\\d+)\\s*(?:周|星期)");
    private static final Pattern PERIOD_WEEKS_CN = Pattern.compile("([一两三四五六七八九十])\\s*(?:周|星期)");
    private static final Pattern PERIOD_MONTHS = Pattern.compile("(\\d+)\\s*(?:个?月)");
    private static final Pattern PERIOD_MONTHS_CN = Pattern.compile("([一两三四五六七八九十])\\s*(?:个?月)");
    private static final Pattern WEIGHT_LOSS = Pattern.compile("减(?:掉|去|轻)?\\s*(\\d+(?:\\.\\d+)?)\\s*(斤|公斤|kg|千克)");
    private static final Pattern WEIGHT_GAIN = Pattern.compile("增(?:加|重)?\\s*(\\d+(?:\\.\\d+)?)\\s*(斤|公斤|kg|千克)");
    private static final Pattern CITY_WITH_SUFFIX = Pattern.compile(
            "(?:我(?:现在|目前)?)?(?:在|住在|位于|常驻)\\s*([\\u4e00-\\u9fa5]{2,8}(?:省|市|区|县))");
    private static final Pattern CITY_DIRECT = Pattern.compile(
            "(?:在|住在|位于|常驻)\\s*(北京|上海|广州|深圳|天津|重庆|成都|杭州|武汉|西安|南京|苏州|"
                    + "郑州|长沙|南昌|宜春|长春|沈阳|青岛|大连|厦门|福州|济南|合肥|昆明|贵阳|南宁|太原|"
                    + "石家庄|哈尔滨|乌鲁木齐|兰州|银川|西宁|拉萨|海口|三亚|呼和浩特|香港|澳门)");
    private static final Pattern MEALS = Pattern.compile("(\\d+)\\s*餐");
    private static final Pattern WEEKLY_TRAINING = Pattern.compile(
            "(?:每周|一周|每星期|一星期)\\s*(?:练|锻炼|运动)?\\s*(\\d{1,2})\\s*次");

    private static final List<String> BULK_TERMS = List.of("增肌", "长肌肉", "练肌肉", "增重", "增壮");
    private static final List<String> CUT_TERMS = List.of("减脂", "减肥", "减重", "瘦身", "掉秤", "瘦下来", "减掉", "降体重", "减斤");
    private static final List<String> EXERCISE_WORDS = List.of(
            "跑步", "慢跑", "游泳", "篮球", "足球", "羽毛球", "跳绳", "骑行", "单车",
            "瑜伽", "健身", "力量", "帕梅拉", "hiit", "有氧");

    private HealthProfileParser() {
    }

    /** 目标方向。 */
    public enum Goal {
        CUT("减脂"),
        BULK("增肌"),
        MAINTAIN("维持");

        private final String chineseName;

        Goal(String chineseName) {
            this.chineseName = chineseName;
        }

        public String chineseName() {
            return chineseName;
        }
    }

    /** 提取结果；性别、身高、体重缺失时对应字段为 null，并列入 missingCritical。 */
    public record Profile(
            Goal goal,
            Boolean male,
            Integer age,
            Double heightCm,
            Double weightKg,
            Integer periodDays,
            Double weightDeltaKg,
            String city,
            String trainingPreference,
            Integer mealsPerDay,
            Integer weeklyTraining) {
    }

    public record ParseResult(Profile profile, List<String> missingCritical) {

        public ParseResult {
            missingCritical = List.copyOf(missingCritical);
        }
    }

    public static ParseResult parse(String text) {
        String value = text == null ? "" : text.strip();
        List<String> missing = new ArrayList<>();
        Optional<Boolean> male = findSex(value);
        if (male.isEmpty()) {
            missing.add("性别");
        }
        Optional<Double> heightCm = findHeight(value);
        if (heightCm.isEmpty()) {
            missing.add("身高");
        }
        Optional<Double> weightKg = findWeight(value);
        if (weightKg.isEmpty()) {
            missing.add("体重");
        }
        Optional<Integer> age = findAge(value);
        Profile profile = new Profile(
                findGoal(value),
                male.orElse(null),
                age.orElse(null),
                heightCm.orElse(null),
                weightKg.orElse(null),
                findPeriodDays(value),
                findWeightDelta(value),
                findCity(value),
                findTrainingPreference(value),
                findMeals(value),
                findWeeklyTraining(value));
        return new ParseResult(profile, missing);
    }

    private static Optional<Boolean> findSex(String value) {
        Matcher labeled = SEX.matcher(value);
        if (labeled.find()) {
            return Optional.of("男".equals(labeled.group(1)));
        }
        Matcher word = SEX_WORD.matcher(value);
        return word.find() ? Optional.of("男".equals(word.group())) : Optional.empty();
    }

    private static Optional<Integer> findAge(String value) {
        Matcher matcher = AGE.matcher(value);
        if (matcher.find()) {
            String age = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            return Optional.of(Integer.parseInt(age));
        }
        return Optional.empty();
    }

    private static Optional<Double> findHeight(String value) {
        // 米制优先："1米75"、"1.75米"，范围校验避免误伤（如"跑了 5 米"）
        Matcher metersCn = HEIGHT_METERS_CN.matcher(value);
        if (metersCn.find()) {
            double major = Double.parseDouble(metersCn.group(1));
            double cm;
            if (metersCn.group(2) != null) {
                int minor = Integer.parseInt(metersCn.group(2));
                cm = Math.floor(major) * 100 + (minor < 10 ? minor * 10 : minor);
            } else {
                cm = major * 100;
            }
            if (cm >= 50 && cm <= 250) {
                return Optional.of(cm);
            }
        }
        // 拉丁米制："1.75m"（要求带小数，避免把"170m"之类的距离误当身高）
        Matcher metersLatin = HEIGHT_METERS_LATIN.matcher(value);
        if (metersLatin.find()) {
            return Optional.of(Double.parseDouble(metersLatin.group(1)) * 100);
        }
        // 厘米或带标签数字："175cm"、"身高 175"；带"身高"标签的个位数数值按"米"理解（如"身高 1.75"）
        Matcher matcher = HEIGHT.matcher(value);
        if (matcher.find()) {
            String height = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            double parsed = Double.parseDouble(height);
            if (matcher.group(1) != null && parsed < 10) {
                return Optional.of(parsed * 100);
            }
            return Optional.of(parsed);
        }
        return Optional.empty();
    }

    private static Optional<Double> findWeight(String value) {
        Matcher matcher = WEIGHT.matcher(value);
        if (matcher.find()) {
            String weight = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            return Optional.of(Double.parseDouble(weight));
        }
        return Optional.empty();
    }

    private static Goal findGoal(String value) {
        String compact = normalize(value);
        for (String term : BULK_TERMS) {
            if (compact.contains(term)) {
                return Goal.BULK;
            }
        }
        for (String term : CUT_TERMS) {
            if (compact.contains(term)) {
                return Goal.CUT;
            }
        }
        Double delta = findWeightDelta(value);
        if (delta != null) {
            return delta > 0 ? Goal.BULK : Goal.CUT;
        }
        return Goal.MAINTAIN;
    }

    private static Integer findPeriodDays(String value) {
        Matcher days = PERIOD_DAYS.matcher(value);
        if (days.find()) {
            return Integer.parseInt(days.group(1));
        }
        Matcher weeks = PERIOD_WEEKS.matcher(value);
        if (weeks.find()) {
            return Integer.parseInt(weeks.group(1)) * 7;
        }
        Matcher weeksCn = PERIOD_WEEKS_CN.matcher(value);
        if (weeksCn.find()) {
            return chineseToInt(weeksCn.group(1)) * 7;
        }
        Matcher months = PERIOD_MONTHS.matcher(value);
        if (months.find()) {
            return Integer.parseInt(months.group(1)) * 30;
        }
        Matcher monthsCn = PERIOD_MONTHS_CN.matcher(value);
        if (monthsCn.find()) {
            return chineseToInt(monthsCn.group(1)) * 30;
        }
        return 30;
    }

    private static int chineseToInt(String value) {
        return switch (value) {
            case "一" -> 1;
            case "二", "两" -> 2;
            case "三" -> 3;
            case "四" -> 4;
            case "五" -> 5;
            case "六" -> 6;
            case "七" -> 7;
            case "八" -> 8;
            case "九" -> 9;
            case "十" -> 10;
            default -> 0;
        };
    }

    private static Double findWeightDelta(String value) {
        Matcher gain = WEIGHT_GAIN.matcher(value);
        if (gain.find()) {
            return toKg(Double.parseDouble(gain.group(1)), gain.group(2));
        }
        Matcher loss = WEIGHT_LOSS.matcher(value);
        if (loss.find()) {
            return -toKg(Double.parseDouble(loss.group(1)), loss.group(2));
        }
        return null;
    }

    private static double toKg(double amount, String unit) {
        return "斤".equals(unit) ? amount / 2 : amount;
    }

    private static String findCity(String value) {
        Matcher withSuffix = CITY_WITH_SUFFIX.matcher(value);
        if (withSuffix.find()) {
            return withSuffix.group(1);
        }
        Matcher direct = CITY_DIRECT.matcher(value);
        return direct.find() ? direct.group(1) : null;
    }

    private static String findTrainingPreference(String value) {
        String compact = normalize(value);
        return EXERCISE_WORDS.stream()
                .filter(compact::contains)
                .findFirst()
                .orElse(null);
    }

    private static Integer findMeals(String value) {
        Matcher matcher = MEALS.matcher(value);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    private static Integer findWeeklyTraining(String value) {
        Matcher matcher = WEEKLY_TRAINING.matcher(value);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{S}\\s]+", "");
    }
}
