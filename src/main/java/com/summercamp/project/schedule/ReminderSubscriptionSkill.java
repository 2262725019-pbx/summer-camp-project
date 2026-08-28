package com.summercamp.project.schedule;

import com.summercamp.project.skill.BotSkill;
import com.summercamp.project.skill.SkillContext;
import com.summercamp.project.skill.SkillResult;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 订阅/退订定时推送（健康提醒、每日天气播报、每日午餐菜单）的指令入口。
 * 支持指定推送时间："订阅天气 北京 8点""健康提醒改到21:30""午餐菜单改到12点"，
 * 未指定时间时使用全局默认（07:30 / 21:00 / 12:00）。
 */
@Component
public class ReminderSubscriptionSkill implements BotSkill {

    public static final String SKILL_NAME = "reminder-subscription";

    private static final List<String> SUBSCRIBE_WEATHER_TERMS = List.of("订阅天气", "开启天气播报");
    private static final List<String> SUBSCRIBE_HEALTH_TERMS = List.of(
            "订阅健康提醒", "订阅健康", "订阅提醒", "开启健康提醒", "开启提醒");
    private static final List<String> SUBSCRIBE_LUNCH_TERMS = List.of("订阅午餐", "开启午餐", "午餐菜单");
    private static final List<String> UNSUBSCRIBE_TERMS = List.of("退订", "取消订阅", "取消提醒");
    private static final List<String> UNSUBSCRIBE_LUNCH_TERMS = List.of("取消午餐", "退订午餐", "关闭午餐", "停止午餐");
    private static final List<String> CHANGE_TERMS = List.of("改到", "改成", "调整到", "调整成");
    private static final List<String> VIEW_TERMS = List.of("我的订阅", "查看订阅", "订阅列表");

    private static final Pattern CITY_WITH_SUFFIX = Pattern.compile(
            "(?:订阅|播报)\\s*天气(?:播报|推送)?\\s*(?:到|在|为)?\\s*([\\u4e00-\\u9fa5]{2,8}(?:省|市|区|县))");
    private static final Pattern CITY_SHORT = Pattern.compile(
            "(?:订阅|播报)\\s*天气(?:播报|推送)?\\s*(?:到|在|为)?\\s*"
                    + "(北京|上海|广州|深圳|天津|重庆|成都|杭州|武汉|西安|南京|苏州|长沙|南昌|宜春"
                    + "|青岛|大连|厦门|福州|济南|合肥|昆明|贵阳|南宁|太原|石家庄|哈尔滨|郑州"
                    + "|兰州|银川|西宁|拉萨|海口|三亚|呼和浩特|香港|澳门)");
    private static final Pattern TIME_HH_MM = Pattern.compile("(\\d{1,2})\\s*[:：]\\s*(\\d{1,2})");
    private static final Pattern TIME_CN = Pattern.compile("(\\d{1,2})\\s*[点时]\\s*(半)?");

    private final ReminderSubscriptionManager subscriptions;

    public ReminderSubscriptionSkill(ReminderSubscriptionManager subscriptions) {
        this.subscriptions = subscriptions;
    }

    @Override
    public String name() {
        return SKILL_NAME;
    }

    @Override
    public int priority() {
        return 55;
    }

    @Override
    public int matchScore(String text) {
        String normalized = normalize(text);
        int score = 0;
        for (String term : VIEW_TERMS) {
            if (normalized.contains(term)) {
                score = Math.max(score, 30 + term.length());
            }
        }
        for (String term : UNSUBSCRIBE_TERMS) {
            if (normalized.contains(term)) {
                score = Math.max(score, 50 + term.length());
            }
        }
        for (String term : UNSUBSCRIBE_LUNCH_TERMS) {
            if (normalized.contains(term)) {
                score = Math.max(score, 55 + term.length());
            }
        }
        for (String term : CHANGE_TERMS) {
            if (normalized.contains(term) && containsAny(normalized, List.of("天气", "健康", "提醒", "午餐"))) {
                score = Math.max(score, 50 + term.length());
            }
        }
        for (String term : SUBSCRIBE_WEATHER_TERMS) {
            if (normalized.contains(term)) {
                score = Math.max(score, 50 + term.length());
            }
        }
        for (String term : SUBSCRIBE_HEALTH_TERMS) {
            if (normalized.contains(term)) {
                score = Math.max(score, 50 + term.length());
            }
        }
        for (String term : SUBSCRIBE_LUNCH_TERMS) {
            if (normalized.contains(term)) {
                score = Math.max(score, 50 + term.length());
            }
        }
        return score;
    }

    @Override
    public SkillResult execute(SkillContext context) {
        String text = context.text() == null ? "" : context.text();
        String normalized = normalize(text);
        String userId = context.userId();

        if (containsAny(normalized, VIEW_TERMS)) {
            return SkillResult.completed(subscriptions.summary(userId));
        }

        if (containsAny(normalized, UNSUBSCRIBE_LUNCH_TERMS)) {
            return subscriptions.unsubscribeLunchMenu(userId)
                    ? SkillResult.completed("已取消每日午餐菜单推送。")
                    : SkillResult.completed("你当前没有订阅每日午餐菜单。");
        }

        if (containsAny(normalized, UNSUBSCRIBE_TERMS)) {
            return handleUnsubscribe(normalized, userId);
        }

        if (containsAny(normalized, CHANGE_TERMS)) {
            return handleChange(normalized, text, userId);
        }

        boolean lunch = containsAny(normalized, SUBSCRIBE_LUNCH_TERMS);
        boolean weather = containsAny(normalized, SUBSCRIBE_WEATHER_TERMS);
        boolean health = containsAny(normalized, SUBSCRIBE_HEALTH_TERMS);
        if (!lunch && !weather && !health) {
            return SkillResult.completed(
                    "可以发送“订阅天气 北京”“订阅健康提醒”“订阅午餐菜单”或“退订提醒”来管理定时推送；"
                            + "订阅时可指定时间，如“订阅天气 北京 8点”“健康提醒改到21:30”“午餐菜单改到12点”。");
        }

        List<String> done = new java.util.ArrayList<>();
        if (lunch) {
            subscriptions.subscribeLunchMenu(userId, parseTime(text));
            done.add("午餐菜单（每天 " + timeText(parseTime(text), "12:00") + "）");
        }
        if (health) {
            subscriptions.subscribeHealth(userId, null, null, parseTime(text));
            done.add("健康提醒（每天 " + timeText(parseTime(text), "21:00") + "）");
        }
        if (weather) {
            String city = extractCity(text);
            if (city == null) {
                return SkillResult.completed(health || lunch
                        ? "已开启。请告诉我要订阅天气的城市，例如：订阅天气 北京。"
                        : "请告诉我要订阅天气的城市，例如：订阅天气 北京 8点。");
            }
            subscriptions.subscribeWeather(userId, city, parseTime(text));
            done.add(city + " 天气播报（每天 " + timeText(parseTime(text), "07:30") + "）");
        }
        return SkillResult.completed(
                "订阅成功：" + String.join("、", done) + "。" + guideHint(lunch, weather, health));
    }

    /** 按本次订阅的类型生成对应的取消/改时提示，避免给出与订阅无关的指令。 */
    private String guideHint(boolean lunch, boolean weather, boolean health) {
        StringBuilder hint = new StringBuilder();
        if (lunch) {
            hint.append("发送“取消午餐菜单”可取消，“午餐菜单改到12点”可改时间。");
        }
        if (weather) {
            if (hint.length() > 0) {
                hint.append(' ');
            }
            hint.append("发送“退订天气”可取消，“天气播报改到8点”可改时间。");
        }
        if (health) {
            if (hint.length() > 0) {
                hint.append(' ');
            }
            hint.append("发送“退订提醒”可取消，“健康提醒改到21:30”可改时间。");
        }
        return hint.length() > 0 ? hint.toString() : "发送“退订提醒”可管理订阅。";
    }

    private SkillResult handleUnsubscribe(String normalized, String userId) {
        List<String> done = new java.util.ArrayList<>();
        if (normalized.contains("午餐") && subscriptions.unsubscribeLunchMenu(userId)) {
            done.add("午餐菜单");
        }
        if (normalized.contains("天气") && subscriptions.unsubscribeWeather(userId)) {
            done.add("天气播报");
        }
        if ((normalized.contains("提醒") || normalized.contains("健康"))
                && subscriptions.unsubscribeHealth(userId)) {
            done.add("健康提醒");
        }
        if (done.isEmpty()) {
            boolean weather = subscriptions.unsubscribeWeather(userId);
            boolean health = subscriptions.unsubscribeHealth(userId);
            boolean lunch = subscriptions.unsubscribeLunchMenu(userId);
            if (weather) {
                done.add("天气播报");
            }
            if (health) {
                done.add("健康提醒");
            }
            if (lunch) {
                done.add("午餐菜单");
            }
        }
        return done.isEmpty()
                ? SkillResult.completed("你当前没有可退订的推送。")
                : SkillResult.completed("已退订：" + String.join("、", done) + "。");
    }

    private SkillResult handleChange(String normalized, String text, String userId) {
        String time = parseTime(text);
        if (time == null) {
            return SkillResult.completed("请给出修改后的时间，例如：健康提醒改到21:30，或 午餐菜单改到12点。");
        }
        if (normalized.contains("午餐")) {
            return subscriptions.updateLunchMenuTime(userId, time)
                    ? SkillResult.completed("午餐菜单推送时间已改为每天 " + time + "。")
                    : SkillResult.completed("你还没有订阅午餐菜单，可先发送“订阅午餐菜单”。");
        }
        if (normalized.contains("天气")) {
            return subscriptions.updateWeatherDigestTime(userId, time)
                    ? SkillResult.completed("天气播报时间已改为每天 " + time + "。")
                    : SkillResult.completed("你还没有订阅天气播报，可先发送“订阅天气 北京 8点”。");
        }
        subscriptions.updateHealthReminderTime(userId, time);
        return SkillResult.completed("健康提醒时间已改为每天 " + time + "。");
    }

    /** 解析"8点""8点半""8:30""下午3点"等为 HH:mm；无法解析返回 null。 */
    String parseTime(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher hhmm = TIME_HH_MM.matcher(text);
        int hour;
        int minute;
        if (hhmm.find()) {
            hour = Integer.parseInt(hhmm.group(1));
            minute = Integer.parseInt(hhmm.group(2));
        } else {
            Matcher cn = TIME_CN.matcher(text);
            if (!cn.find()) {
                return null;
            }
            hour = Integer.parseInt(cn.group(1));
            minute = "半".equals(cn.group(2)) ? 30 : 0;
        }
        String normalized = normalize(text);
        if (containsAny(normalized, List.of("下午", "晚上", "傍晚", "夜里", "夜间")) && hour <= 12) {
            hour += 12;
        }
        if (hour > 23 || minute > 59) {
            return null;
        }
        return String.format(Locale.ROOT, "%02d:%02d", hour, minute);
    }

    private String timeText(String time, String fallback) {
        return time == null ? fallback : time;
    }

    private String extractCity(String text) {
        Matcher withSuffix = CITY_WITH_SUFFIX.matcher(text);
        if (withSuffix.find()) {
            return withSuffix.group(1);
        }
        Matcher shortName = CITY_SHORT.matcher(text);
        return shortName.find() ? shortName.group(1) : null;
    }

    private boolean containsAny(String normalized, List<String> terms) {
        return terms.stream().anyMatch(normalized::contains);
    }

    private String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{S}\\s]+", "");
    }
}
