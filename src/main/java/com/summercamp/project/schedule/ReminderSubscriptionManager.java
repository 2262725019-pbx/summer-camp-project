package com.summercamp.project.schedule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 定时推送的订阅表。一个用户一条记录，可同时订阅健康提醒与天气播报。
 * 变更后立即写入 JSON 文件，重启时自动加载恢复。
 */
@Component
public class ReminderSubscriptionManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReminderSubscriptionManager.class);

    /** 一条订阅：健康提醒（目标+热量+可选时间）与天气播报（城市+可选时间）。时间 null 表示用全局默认。 */
    public record Subscription(
            String userId,
            boolean healthReminder,
            String goalChinese,
            Integer targetCalories,
            String city,
            String healthReminderTime,
            String weatherDigestTime,
            long createdAtMillis) {

        public Subscription {
            userId = userId == null ? "" : userId.strip();
        }

        public boolean weatherSubscribed() {
            return city != null && !city.isBlank();
        }
    }

    private final ObjectMapper objectMapper;
    private final Path filePath;
    private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();

    public ReminderSubscriptionManager(
            ObjectMapper objectMapper,
            @Value("${schedule.subscription-file:runtime/subscriptions.json}") String filePath) {
        this.objectMapper = objectMapper;
        this.filePath = Path.of(filePath);
        load();
    }

    /** 登记或更新健康提醒订阅（健康规划生成成功后调用，或用户手动订阅）。时间 null 表示用全局默认。 */
    public void subscribeHealth(String userId, String goalChinese, Integer targetCalories) {
        subscribeHealth(userId, goalChinese, targetCalories, null);
    }

    /** 登记或更新健康提醒订阅，并指定推送时间（HH:mm，null 用全局默认）。 */
    public void subscribeHealth(String userId, String goalChinese, Integer targetCalories, String time) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        Subscription previous = subscriptions.get(userId);
        Subscription updated = new Subscription(
                userId,
                true,
                goalChinese,
                targetCalories,
                previous == null ? null : previous.city(),
                time,
                previous == null ? null : previous.weatherDigestTime(),
                previous == null ? System.currentTimeMillis() : previous.createdAtMillis());
        subscriptions.put(userId, updated);
        save();
    }

    /** 登记或更新天气播报订阅。时间 null 表示用全局默认。 */
    public void subscribeWeather(String userId, String city) {
        subscribeWeather(userId, city, null);
    }

    /** 登记或更新天气播报订阅，并指定推送时间（HH:mm，null 用全局默认）。 */
    public void subscribeWeather(String userId, String city, String time) {
        if (userId == null || userId.isBlank() || city == null || city.isBlank()) {
            return;
        }
        Subscription previous = subscriptions.get(userId);
        Subscription updated = new Subscription(
                userId,
                previous != null && previous.healthReminder(),
                previous == null ? null : previous.goalChinese(),
                previous == null ? null : previous.targetCalories(),
                city.strip(),
                previous == null ? null : previous.healthReminderTime(),
                time,
                previous == null ? System.currentTimeMillis() : previous.createdAtMillis());
        subscriptions.put(userId, updated);
        save();
    }

    /** 只修改健康提醒的推送时间；未订阅则按无目标资料创建。 */
    public void updateHealthReminderTime(String userId, String time) {
        Subscription previous = subscriptions.get(userId);
        Subscription updated = new Subscription(
                userId,
                true,
                previous == null ? null : previous.goalChinese(),
                previous == null ? null : previous.targetCalories(),
                previous == null ? null : previous.city(),
                time,
                previous == null ? null : previous.weatherDigestTime(),
                previous == null ? System.currentTimeMillis() : previous.createdAtMillis());
        subscriptions.put(userId, updated);
        save();
    }

    /** 只修改天气播报的推送时间。 */
    public boolean updateWeatherDigestTime(String userId, String time) {
        Subscription previous = subscriptions.get(userId);
        if (previous == null || !previous.weatherSubscribed()) {
            return false;
        }
        subscriptions.put(userId, new Subscription(
                userId,
                previous.healthReminder(),
                previous.goalChinese(),
                previous.targetCalories(),
                previous.city(),
                previous.healthReminderTime(),
                time,
                previous.createdAtMillis()));
        save();
        return true;
    }

    /** 退订健康提醒；若天气也未订阅则整条移除。 */
    public boolean unsubscribeHealth(String userId) {
        Subscription current = subscriptions.get(userId);
        if (current == null || !current.healthReminder()) {
            return false;
        }
        if (current.weatherSubscribed()) {
            subscriptions.put(userId, new Subscription(
                    userId, false, null, null, current.city(), null,
                    current.weatherDigestTime(), current.createdAtMillis()));
        } else {
            subscriptions.remove(userId);
        }
        save();
        return true;
    }

    /** 退订天气播报；若健康提醒也未订阅则整条移除。 */
    public boolean unsubscribeWeather(String userId) {
        Subscription current = subscriptions.get(userId);
        if (current == null || !current.weatherSubscribed()) {
            return false;
        }
        if (current.healthReminder()) {
            subscriptions.put(userId, new Subscription(
                    userId, true, current.goalChinese(), current.targetCalories(), null,
                    current.healthReminderTime(), null, current.createdAtMillis()));
        } else {
            subscriptions.remove(userId);
        }
        save();
        return true;
    }

    public Optional<Subscription> find(String userId) {
        return userId == null ? Optional.empty() : Optional.ofNullable(subscriptions.get(userId));
    }

    public List<Subscription> allWeatherSubscribers() {
        return subscriptions.values().stream().filter(Subscription::weatherSubscribed).toList();
    }

    public List<Subscription> allHealthSubscribers() {
        return subscriptions.values().stream().filter(Subscription::healthReminder).toList();
    }

    /** 人类可读的订阅概览，用于"查看订阅"回复。 */
    public String summary(String userId) {
        Optional<Subscription> found = find(userId);
        if (found.isEmpty()) {
            return "你还没有订阅任何提醒。可发送“订阅天气 北京”或“订阅健康提醒”开启。";
        }
        Subscription subscription = found.get();
        List<String> parts = new ArrayList<>();
        if (subscription.healthReminder()) {
            parts.add("健康提醒（每天 " + timeOrDefault(subscription.healthReminderTime(), "21:00") + "）");
        }
        if (subscription.weatherSubscribed()) {
            parts.add(subscription.city() + " 天气播报（每天 "
                    + timeOrDefault(subscription.weatherDigestTime(), "07:30") + "）");
        }
        return "当前订阅：" + String.join("、", parts)
                + "。发送“退订提醒/退订天气”可取消，发送“健康提醒改到21:30”可改时间。";
    }

    private String timeOrDefault(String time, String fallback) {
        return time == null || time.isBlank() ? fallback : time;
    }

    // ---------------------------------------------------------------- 持久化

    private void load() {
        if (!Files.exists(filePath)) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(Files.readAllBytes(filePath));
            if (!root.isArray()) {
                LOGGER.warn("订阅文件格式异常，忽略：{}", filePath);
                return;
            }
            for (JsonNode node : root) {
                String userId = node.path("userId").asText();
                if (userId.isBlank()) {
                    continue;
                }
                Subscription subscription = new Subscription(
                        userId,
                        node.path("healthReminder").asBoolean(false),
                        node.hasNonNull("goalChinese") ? node.path("goalChinese").asText() : null,
                        node.hasNonNull("targetCalories")
                                ? node.path("targetCalories").asInt()
                                : null,
                        node.hasNonNull("city") ? node.path("city").asText() : null,
                        node.hasNonNull("healthReminderTime")
                                ? node.path("healthReminderTime").asText()
                                : null,
                        node.hasNonNull("weatherDigestTime")
                                ? node.path("weatherDigestTime").asText()
                                : null,
                        node.path("createdAtMillis").asLong(System.currentTimeMillis()));
                subscriptions.put(userId, subscription);
            }
        } catch (IOException exception) {
            LOGGER.warn("读取订阅文件失败：{}", exception.getMessage());
        }
    }

    private void save() {
        try {
            Path parent = filePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            ArrayNode array = objectMapper.createArrayNode();
            for (Subscription subscription : subscriptions.values()) {
                ObjectNode node = array.addObject();
                node.put("userId", subscription.userId());
                node.put("healthReminder", subscription.healthReminder());
                if (subscription.goalChinese() != null) {
                    node.put("goalChinese", subscription.goalChinese());
                }
                if (subscription.targetCalories() != null) {
                    node.put("targetCalories", subscription.targetCalories());
                }
                if (subscription.city() != null) {
                    node.put("city", subscription.city());
                }
                if (subscription.healthReminderTime() != null) {
                    node.put("healthReminderTime", subscription.healthReminderTime());
                }
                if (subscription.weatherDigestTime() != null) {
                    node.put("weatherDigestTime", subscription.weatherDigestTime());
                }
                node.put("createdAtMillis", subscription.createdAtMillis());
            }
            Files.writeString(filePath, objectMapper.writeValueAsString(array));
        } catch (IOException exception) {
            LOGGER.warn("写入订阅文件失败：{}", exception.getMessage());
        }
    }
}
