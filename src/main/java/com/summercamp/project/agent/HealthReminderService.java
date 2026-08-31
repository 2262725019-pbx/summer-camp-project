package com.summercamp.project.agent;

import com.summercamp.project.agent.store.CompletedHealthPlanStore;
import com.summercamp.project.agent.store.CompletedHealthPlanStore.CompletedHealthPlan;
import com.summercamp.project.agent.store.AgentStateDatabase;
import com.summercamp.project.config.HealthReminderProperties;
import com.summercamp.project.wechat.WechatGateway;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class HealthReminderService {

    private static final Logger LOGGER = LoggerFactory.getLogger(HealthReminderService.class);
    private static final Pattern ENABLE = Pattern.compile(
            "^(?:开启|设置)(?:每天|每日)?健康提醒(?:时间)?[：:=\\s]*(\\d{1,2}):(\\d{2})$");
    private static final Pattern DAY_LINE = Pattern.compile("(?m)^第(\\d+)天：(.+)$");

    private final HealthReminderProperties properties;
    private final CompletedHealthPlanStore planStore;
    private final WechatGateway gateway;
    private final Clock clock;
    private final AgentStateDatabase database;
    private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();

    @Autowired
    public HealthReminderService(
            HealthReminderProperties properties,
            CompletedHealthPlanStore planStore,
            WechatGateway gateway,
            ObjectProvider<AgentStateDatabase> databaseProvider) {
        this(properties, planStore, gateway, Clock.systemUTC(), databaseProvider.getIfAvailable());
    }

    HealthReminderService(
            HealthReminderProperties properties,
            CompletedHealthPlanStore planStore,
            WechatGateway gateway,
            Clock clock) {
        this(properties, planStore, gateway, clock, null);
    }

    HealthReminderService(
            HealthReminderProperties properties,
            CompletedHealthPlanStore planStore,
            WechatGateway gateway,
            Clock clock,
            AgentStateDatabase database) {
        this.properties = properties;
        this.planStore = planStore;
        this.gateway = gateway;
        this.clock = clock;
        this.database = database;
        if (database != null) {
            database.loadReminders().forEach((userId, stored) -> subscriptions.put(
                    userId, new Subscription(stored.time(), stored.lastSentDate())));
        }
    }

    public Optional<String> handleCommand(String userId, String text) {
        String command = text == null ? "" : text.strip();
        Matcher matcher = ENABLE.matcher(command);
        if (matcher.matches()) {
            if (!properties.enabled()) {
                return Optional.of("健康提醒功能已在配置中关闭。");
            }
            int hour = Integer.parseInt(matcher.group(1));
            int minute = Integer.parseInt(matcher.group(2));
            if (hour > 23 || minute > 59) {
                return Optional.of("提醒时间格式不正确，请使用 00:00～23:59，例如：开启每日健康提醒 07:30。");
            }
            Subscription subscription = new Subscription(LocalTime.of(hour, minute), null);
            subscriptions.put(userId, subscription);
            persist(userId, subscription);
            return Optional.of("已开启每日健康提醒，时间为 %02d:%02d。程序运行期间会发送当天计划；发送“关闭健康提醒”可取消。"
                    .formatted(hour, minute));
        }
        if ("关闭健康提醒".equals(command) || "取消健康提醒".equals(command)) {
            remove(userId);
            return Optional.of("已关闭每日健康提醒。");
        }
        if ("查看健康提醒".equals(command)) {
            Subscription subscription = subscriptions.get(userId);
            return Optional.of(subscription == null
                    ? "当前没有开启健康提醒。"
                    : "健康提醒已开启，每天 %02d:%02d 发送。"
                            .formatted(subscription.time().getHour(), subscription.time().getMinute()));
        }
        return Optional.empty();
    }

    public void clear(String userId) {
        remove(userId);
    }

    @Scheduled(fixedDelayString = "${agent.health.reminder.scan-interval:30s}")
    public void dispatchDue() {
        dispatchDue(clock.instant());
    }

    void dispatchDue(Instant now) {
        if (!properties.enabled()) {
            return;
        }
        properties.validate();
        ZonedDateTime localNow = now.atZone(properties.zone());
        subscriptions.forEach((userId, subscription) -> {
            if (subscription.lastSentDate() != null
                    && subscription.lastSentDate().equals(localNow.toLocalDate())) {
                return;
            }
            if (localNow.toLocalTime().isBefore(subscription.time())) {
                return;
            }
            Optional<CompletedHealthPlan> plan = planStore.latest(userId);
            if (plan.isEmpty()) {
                remove(userId, subscription);
                return;
            }
            sendReminder(userId, subscription, plan.get(), localNow);
        });
    }

    private void sendReminder(
            String userId,
            Subscription subscription,
            CompletedHealthPlan plan,
            ZonedDateTime localNow) {
        LocalDate start = plan.createdAt().atZone(properties.zone()).toLocalDate();
        int day = (int) ChronoUnit.DAYS.between(start, localNow.toLocalDate()) + 1;
        if (day < 1) {
            return;
        }
        if (day > plan.goal().days()) {
            remove(userId, subscription);
            LOGGER.info("健康计划周期已结束，自动关闭对应提醒");
            return;
        }
        String dailyLine = findDayLine(plan.artifact().content(), day)
                .orElse("按计划完成当天饮食、训练或恢复安排。");
        String message = "今日健康提醒（第 " + day + " 天）\n" + dailyLine
                + "\n请先查看当天实际天气，并记录睡眠、饮水和身体感受。";
        try {
            gateway.sendText(userId, message);
            Subscription updated = new Subscription(subscription.time(), localNow.toLocalDate());
            if (subscriptions.replace(userId, subscription, updated)) {
                persist(userId, updated);
            }
        } catch (IOException exception) {
            LOGGER.warn("健康提醒发送失败：error={}", exception.getClass().getSimpleName());
        }
    }

    private Optional<String> findDayLine(String content, int day) {
        Matcher matcher = DAY_LINE.matcher(content);
        while (matcher.find()) {
            if (Integer.parseInt(matcher.group(1)) == day) {
                return Optional.of("第" + day + "天：" + matcher.group(2).strip());
            }
        }
        return Optional.empty();
    }

    private void persist(String userId, Subscription subscription) {
        if (database == null) {
            return;
        }
        try {
            database.saveReminder(userId, subscription.time(), subscription.lastSentDate());
        } catch (RuntimeException exception) {
            LOGGER.warn("健康提醒状态保存失败：error={}", exception.getClass().getSimpleName());
        }
    }

    private void remove(String userId) {
        subscriptions.remove(userId);
        deletePersisted(userId);
    }

    private void remove(String userId, Subscription expected) {
        if (subscriptions.remove(userId, expected)) {
            deletePersisted(userId);
        }
    }

    private void deletePersisted(String userId) {
        if (database == null) {
            return;
        }
        try {
            database.deleteReminder(userId);
        } catch (RuntimeException exception) {
            LOGGER.warn("健康提醒状态删除失败：error={}", exception.getClass().getSimpleName());
        }
    }

    private record Subscription(LocalTime time, LocalDate lastSentDate) {
    }
}
