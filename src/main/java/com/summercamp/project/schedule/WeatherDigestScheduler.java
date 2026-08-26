package com.summercamp.project.schedule;

import com.summercamp.project.schedule.ReminderSubscriptionManager.Subscription;
import com.summercamp.project.weather.WeatherClient;
import com.summercamp.project.weather.WeatherPeriod;
import com.summercamp.project.weather.WeatherReport;
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
 * 天气播报：每分钟扫描，按每个订阅者自己的时间（未指定用全局默认 07:30）推送当天天气与运动建议。
 * 每个用户每天最多推送一次；单条失败只记日志，不影响其他订阅者。
 */
@Component
public class WeatherDigestScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(WeatherDigestScheduler.class);
    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalTime FALLBACK_TIME = LocalTime.of(7, 30);

    private final ReminderSubscriptionManager subscriptions;
    private final WeatherClient weatherClient;
    private final WechatGateway gateway;
    private final LocalTime defaultTime;
    private final Clock clock;
    private final Map<String, LocalDate> lastPushed = new ConcurrentHashMap<>();

    @Autowired
    public WeatherDigestScheduler(
            ReminderSubscriptionManager subscriptions,
            WeatherClient weatherClient,
            WechatGateway gateway,
            @Value("${schedule.weather-digest:07:30}") String defaultTime) {
        this(subscriptions, weatherClient, gateway, parseDefaultTime(defaultTime), Clock.systemUTC());
    }

    WeatherDigestScheduler(
            ReminderSubscriptionManager subscriptions,
            WeatherClient weatherClient,
            WechatGateway gateway,
            LocalTime defaultTime,
            Clock clock) {
        this.subscriptions = subscriptions;
        this.weatherClient = weatherClient;
        this.gateway = gateway;
        this.defaultTime = defaultTime;
        this.clock = clock;
    }

    @Scheduled(cron = "0 * * * * *", zone = "Asia/Shanghai")
    public void pushDueWeather() {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(CHINA_ZONE));
        LocalDate today = now.toLocalDate();
        LocalTime currentMinute = now.toLocalTime().withSecond(0).withNano(0);
        for (Subscription subscription : subscriptions.allWeatherSubscribers()) {
            LocalTime scheduled = subscription.weatherDigestTime() == null
                    ? defaultTime
                    : parseTime(subscription.weatherDigestTime());
            if (scheduled == null || !currentMinute.equals(scheduled)) {
                continue;
            }
            String key = subscription.userId() + ":weather";
            if (today.equals(lastPushed.get(key))) {
                continue;
            }
            try {
                WeatherReport report = weatherClient.query(subscription.city(), WeatherPeriod.TODAY);
                gateway.sendText(
                        subscription.userId(),
                        report.formatChinese() + "\n\n" + WeatherAdvice.adviceFor(report));
                lastPushed.put(key, today);
            } catch (Exception exception) {
                LOGGER.warn("天气播报推送失败（{}）：{}", subscription.userId(), exception.getMessage());
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
            LOGGER.warn("天气播报默认时间无效（{}），使用 07:30", time);
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
