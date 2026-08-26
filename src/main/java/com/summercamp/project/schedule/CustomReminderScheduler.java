package com.summercamp.project.schedule;

import com.summercamp.project.schedule.ReminderStore.Reminder;
import com.summercamp.project.wechat.WechatGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每分钟扫描一次提醒表，到点即推送。发送成功才标记触发，
 * 失败保留待下轮重试（防止提醒丢失）；单条失败不影响其他提醒。
 */
@Component
public class CustomReminderScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(CustomReminderScheduler.class);

    private final ReminderStore reminderStore;
    private final WechatGateway gateway;

    public CustomReminderScheduler(ReminderStore reminderStore, WechatGateway gateway) {
        this.reminderStore = reminderStore;
        this.gateway = gateway;
    }

    @Scheduled(cron = "0 * * * * *", zone = "Asia/Shanghai")
    public void pushDueReminders() {
        long now = System.currentTimeMillis();
        for (Reminder reminder : reminderStore.dueAt(now)) {
            try {
                gateway.sendText(reminder.userId(), "⏰ 提醒：" + reminder.content());
                reminderStore.markTriggered(reminder, now);
                LOGGER.info("已触发提醒 userId={} content={} repeatDaily={}",
                        reminder.userId(), reminder.content(), reminder.repeatDaily());
            } catch (Exception exception) {
                LOGGER.warn("提醒推送失败（{}，{}）：{}",
                        reminder.userId(), reminder.content(), exception.getMessage());
            }
        }
    }
}
