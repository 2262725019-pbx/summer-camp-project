package com.summercamp.project.skill.health;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic core-input gate for Agent exercise requests. */
final class ExerciseSkillInputCompleteness {

    private static final Pattern LOCATION = labeledValue("所在地|所在城市|城市|地区");
    private static final Pattern GOAL = labeledValue("运动目标|健身目标|锻炼目标");
    private static final Pattern FREQUENCY = Pattern.compile(
            "(?:每周训练|每周运动|周训练|训练频率)\\s*[：:=]?\\s*([1-7一二三四五六七])\\s*次");
    private static final Pattern DURATION_MINUTES = Pattern.compile(
            "(?:每次训练|每次运动|单次训练|单次运动|每次时长)\\s*[：:=]?\\s*(\\d{1,3})\\s*(?:分钟|min)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DURATION_HOURS = Pattern.compile(
            "(?:每次训练|每次运动|单次训练|单次运动|每次时长)\\s*[：:=]?\\s*(\\d(?:\\.\\d)?)\\s*(?:小时|h)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HEALTH = Pattern.compile(
            "(?:健康确认|健康状态|身体状况|身体情况|疾病情况)\\s*[：:=]?\\s*"
                    + "[^\\r\\n，,。；;]{0,20}(?:健康成人|身体健康|无明显身体不适|无慢性病|无基础疾病|无伤病)");

    boolean isComplete(String request) {
        String text = request == null ? "" : request.strip();
        return hasValue(LOCATION, text)
                && hasValue(GOAL, text)
                && validFrequency(text)
                && validDuration(text)
                && HEALTH.matcher(text).find();
    }

    private boolean validFrequency(String text) {
        Matcher matcher = FREQUENCY.matcher(text);
        if (!matcher.find()) {
            return false;
        }
        String value = matcher.group(1);
        return value.length() == 1 && "1234567一二三四五六七".contains(value);
    }

    private boolean validDuration(String text) {
        Matcher minutes = DURATION_MINUTES.matcher(text);
        if (minutes.find()) {
            int value = Integer.parseInt(minutes.group(1));
            return value >= 10 && value <= 300;
        }
        Matcher hours = DURATION_HOURS.matcher(text);
        if (!hours.find()) {
            return false;
        }
        double value = Double.parseDouble(hours.group(1));
        return value >= 0.2 && value <= 5.0;
    }

    private boolean hasValue(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() && !matcher.group(1).isBlank();
    }

    private static Pattern labeledValue(String labels) {
        return Pattern.compile(
                "(?:" + labels + ")\\s*[：:=]\\s*([^\\r\\n，,。；;]{1,30})");
    }
}
