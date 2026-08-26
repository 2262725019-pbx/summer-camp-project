package com.summercamp.project.schedule;

import com.summercamp.project.schedule.ReminderSubscriptionManager.Subscription;

/**
 * 每晚健康打卡提醒文案（纯函数，便于单测）。
 */
public final class HealthReminderText {

    private HealthReminderText() {
    }

    public static String build(Subscription subscription) {
        StringBuilder message = new StringBuilder("晚上好，健康小管家来打卡提醒：");
        if (subscription.goalChinese() != null || subscription.targetCalories() != null) {
            message.append("你的目标是");
            if (subscription.goalChinese() != null) {
                message.append(subscription.goalChinese());
            }
            if (subscription.targetCalories() != null) {
                message.append("，建议每日摄入控制在 ")
                        .append(subscription.targetCalories())
                        .append(" 千卡左右");
            }
            message.append("；");
        }
        message.append("睡前 30 分钟放下手机，保证 7～9 小时睡眠；每周固定时间称重并记录，坚持最重要。");
        return message.toString();
    }
}
