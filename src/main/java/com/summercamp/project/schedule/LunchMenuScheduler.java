package com.summercamp.project.schedule;

import com.summercamp.project.schedule.ReminderSubscriptionManager.Subscription;
import com.summercamp.project.skill.nutrition.FoodCatalog;
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
 * 每日午餐菜单：每分钟扫描，按每个订阅者自己的时间（未指定用全局默认 12:00）推送当天生成的午餐菜单。
 * 每个用户每天最多推送一次；单条失败只记日志，不影响其他订阅者。
 */
@Component
public class LunchMenuScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(LunchMenuScheduler.class);
    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalTime FALLBACK_TIME = LocalTime.of(12, 0);

    private final ReminderSubscriptionManager subscriptions;
    private final FoodCatalog foods;
    private final WechatGateway gateway;
    private final LocalTime defaultTime;
    private final Clock clock;
    private final Map<String, LocalDate> lastPushed = new ConcurrentHashMap<>();

    @Autowired
    public LunchMenuScheduler(
            ReminderSubscriptionManager subscriptions,
            FoodCatalog foods,
            WechatGateway gateway,
            @Value("${schedule.lunch-menu:12:00}") String defaultTime) {
        this(subscriptions, foods, gateway, parseDefaultTime(defaultTime), Clock.systemUTC());
    }

    LunchMenuScheduler(
            ReminderSubscriptionManager subscriptions,
            FoodCatalog foods,
            WechatGateway gateway,
            LocalTime defaultTime,
            Clock clock) {
        this.subscriptions = subscriptions;
        this.foods = foods;
        this.gateway = gateway;
        this.defaultTime = defaultTime;
        this.clock = clock;
    }

    @Scheduled(cron = "0 * * * * *", zone = "Asia/Shanghai")
    public void pushDueLunchMenus() {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(CHINA_ZONE));
        LocalDate today = now.toLocalDate();
        LocalTime currentMinute = now.toLocalTime().withSecond(0).withNano(0);
        for (Subscription subscription : subscriptions.allLunchMenuSubscribers()) {
            LocalTime scheduled = subscription.lunchMenuTime() == null
                    ? defaultTime
                    : parseTime(subscription.lunchMenuTime());
            if (scheduled == null || !currentMinute.equals(scheduled)) {
                continue;
            }
            String key = subscription.userId() + ":lunch";
            if (today.equals(lastPushed.get(key))) {
                continue;
            }
            try {
                gateway.sendText(subscription.userId(), LunchMenuText.build(today, foods));
                lastPushed.put(key, today);
            } catch (Exception exception) {
                LOGGER.warn("午餐菜单推送失败（{}）：{}", subscription.userId(), exception.getMessage());
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
            LOGGER.warn("午餐菜单默认时间无效（{}），使用 12:00", time);
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
