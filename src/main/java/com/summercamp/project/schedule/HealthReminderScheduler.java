package com.summercamp.project.schedule;

import com.summercamp.project.schedule.ReminderSubscriptionManager.Subscription;
import com.summercamp.project.wechat.WechatGateway;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 健康打卡提醒：每分钟扫描，按每个订阅者自己的时间（未指定用全局默认 21:00）推送。
 * 每个用户每天最多推送一次；单条失败只记日志，不影响其他订阅者。
 */
@Component
public class HealthReminderScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(HealthReminderScheduler.class);
    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalTime FALLBACK_TIME = LocalTime.of(21, 0);

    private final ReminderSubscriptionManager subscriptions;
    private final WechatGateway gateway;
    private final LocalTime defaultTime;
    private final Clock clock;
    private final Map<String, LocalDate> lastPushed = new ConcurrentHashMap<>();

    @Autowired
    public HealthReminderScheduler(
            ReminderSubscriptionManager subscriptions,
            WechatGateway gateway,
            @Value("${schedule.health-reminder:21:00}") String defaultTime) {
        this(subscriptions, gateway, parseDefaultTime(defaultTime), Clock.systemUTC());
    }

    HealthReminderScheduler(
            ReminderSubscriptionManager subscriptions,
            WechatGateway gateway,
            LocalTime defaultTime,
            Clock clock) {
        this.subscriptions = subscriptions;
        this.gateway = gateway;
        this.defaultTime = defaultTime;
        this.clock = clock;
    }

    @Scheduled(cron = "0 * * * * *", zone = "Asia/Shanghai")
    public void pushDueHealthReminders() {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(CHINA_ZONE));
        LocalDate today = now.toLocalDate();
        LocalTime currentMinute = now.toLocalTime().withSecond(0).withNano(0);
        for (Subscription subscription : subscriptions.allHealthSubscribers()) {
            LocalTime scheduled = subscription.healthReminderTime() == null
                    ? defaultTime
                    : parseTime(subscription.healthReminderTime());
            if (scheduled == null || !currentMinute.equals(scheduled)) {
                continue;
            }
            String key = subscription.userId() + ":health";
            if (today.equals(lastPushed.get(key))) {
                continue;
            }
            try {
                gateway.sendText(subscription.userId(), HealthReminderText.build(subscription));
                lastPushed.put(key, today);
            } catch (Exception exception) {
                LOGGER.warn("健康提醒推送失败（{}）：{}", subscription.userId(), exception.getMessage());
            }
        }
    }

    private static LocalTime parseDefaultTime(String time) {
        if (time == null || time.isBlank()) {
            return FALLBACK_TIME;
        }
        try {
            return LocalTime.parse(time);
        } catch (RuntimeException exception) {
            LOGGER.warn("健康提醒默认时间无效（{}），使用 21:00", time);
            return FALLBACK_TIME;
        }
    }

    private LocalTime parseTime(String time) {
        try {
            return LocalTime.parse(time);
        } catch (RuntimeException exception) {
            LOGGER.warn("订阅时间格式无效（{}），本次跳过", time);
            return null;
        }
    }
}
