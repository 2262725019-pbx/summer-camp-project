package com.summercamp.project.skill.health;

import com.summercamp.project.skill.SkillContext;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative Agent-only exercise guidance used after typed transient provider exhaustion. */
final class DeterministicExerciseFallback {
    private static final Pattern FREQUENCY = Pattern.compile(
            "(?:每周训练|每周运动|周训练|训练频率)\\s*[：:=]?\\s*([1-7一二三四五六七])\\s*次");
    private static final Pattern MINUTES = Pattern.compile(
            "(?:每次训练|每次运动|单次训练|单次运动|每次时长)\\s*[：:=]?\\s*(\\d{1,3})\\s*(?:分钟|min)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HOURS = Pattern.compile(
            "(?:每次训练|每次运动|单次训练|单次运动|每次时长)\\s*[：:=]?\\s*(\\d(?:\\.\\d)?)\\s*(?:小时|h)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern GOAL = labeled("运动目标|健身目标|锻炼目标");
    private static final Pattern PREFERENCE = labeled("运动偏好|运动喜好|偏好");

    String render(SkillContext context) {
        String request = context.text();
        int frequency = frequency(request);
        int duration = durationMinutes(request);
        Allocation allocation = allocate(duration);
        String goal = value(GOAL, request, "一般体能与健康维护");
        String preference = value(PREFERENCE, request, "快走和自重训练");
        String weatherPrinciple = context.trustedContext().weatherObservation().isPresent()
                ? "已结合当前运行中取得的近期天气：天气不适合户外时改为室内自重训练，"
                        + "不重复查询天气。"
                : "训练当天先查看天气；高温、降雨或空气状况不佳时改为室内训练。";
        return """
                运动执行参考（健康成人通用方案）
                - 目标：%s；偏好：%s。
                - 频次：每周正式训练%d次；恢复日仅安排轻松散步或舒缓拉伸，不计正式训练。
                - 单次总时长：%d分钟。热身%d分钟，自重主体%d分钟，快走或轻有氧%d分钟，拉伸%d分钟；合计%d分钟。
                - 基础动作：深蹲、跪姿或标准俯卧撑、臀桥、鸟狗式；保持动作稳定，逐步增加难度。
                - 室内外原则：%s
                - 安全：全程保留可控余力；疼痛、眩晕、胸闷或明显不适时立即停止并寻求专业帮助。
                """.formatted(
                goal,
                preference,
                frequency,
                duration,
                allocation.warmup(),
                allocation.main(),
                allocation.cardio(),
                allocation.stretch(),
                allocation.total(),
                weatherPrinciple).strip();
    }

    private Allocation allocate(int total) {
        int warmup = Math.max(1, total / 6);
        int main = Math.max(1, total / 2);
        int cardio = Math.max(1, total / 6);
        int stretch = total - warmup - main - cardio;
        if (stretch <= 0) {
            stretch = 1;
            main = total - warmup - cardio - stretch;
        }
        return new Allocation(warmup, main, cardio, stretch);
    }

    private int frequency(String request) {
        Matcher matcher = FREQUENCY.matcher(request);
        if (!matcher.find()) {
            throw new IllegalArgumentException("complete exercise request must contain frequency");
        }
        return switch (matcher.group(1)) {
            case "一" -> 1;
            case "二" -> 2;
            case "三" -> 3;
            case "四" -> 4;
            case "五" -> 5;
            case "六" -> 6;
            case "七" -> 7;
            default -> Integer.parseInt(matcher.group(1));
        };
    }

    private int durationMinutes(String request) {
        Matcher minutes = MINUTES.matcher(request);
        if (minutes.find()) {
            return Integer.parseInt(minutes.group(1));
        }
        Matcher hours = HOURS.matcher(request);
        if (hours.find()) {
            return (int) Math.round(Double.parseDouble(hours.group(1)) * 60);
        }
        throw new IllegalArgumentException("complete exercise request must contain duration");
    }

    private String value(Pattern pattern, String request, String fallback) {
        Matcher matcher = pattern.matcher(request);
        return matcher.find() ? matcher.group(1).strip() : fallback;
    }

    private static Pattern labeled(String labels) {
        return Pattern.compile("(?:" + labels + ")\\s*[：:=]\\s*([^\\r\\n，,。；;]{1,30})");
    }

    private record Allocation(int warmup, int main, int cardio, int stretch) {
        int total() {
            return warmup + main + cardio + stretch;
        }
    }
}
