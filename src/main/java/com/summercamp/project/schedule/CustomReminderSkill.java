package com.summercamp.project.schedule;

import com.summercamp.project.schedule.ReminderStore.Reminder;
import com.summercamp.project.skill.BotSkill;
import com.summercamp.project.skill.SkillContext;
import com.summercamp.project.skill.SkillResult;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 自定义提醒的管理指令："我的提醒/查看提醒"列出，"取消提醒 2/删除提醒 1"取消。
 * 注意"提醒我明天上午10点交作业"这类设置请求由大模型调用 add_reminder 工具处理，
 * 不在本 Skill 触发范围内。
 */
@Component
public class CustomReminderSkill implements BotSkill {

    public static final String SKILL_NAME = "custom-reminder-manage";

    private static final Logger LOGGER = LoggerFactory.getLogger(CustomReminderSkill.class);

    private static final List<String> VIEW_TERMS = List.of("我的提醒", "查看提醒", "提醒列表");
    private static final List<String> CANCEL_TERMS = List.of("取消提醒", "删除提醒", "移除提醒");

    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.CHINA);
    private static final Pattern INDEX = Pattern.compile("(\\d+)");

    private final ReminderStore reminderStore;

    public CustomReminderSkill(ReminderStore reminderStore) {
        this.reminderStore = reminderStore;
    }

    @Override
    public String name() {
        return SKILL_NAME;
    }

    @Override
    public int priority() {
        return 54;
    }

    @Override
    public int matchScore(String text) {
        String normalized = normalize(text);
        int score = 0;
        for (String term : CANCEL_TERMS) {
            if (normalized.contains(term)) {
                score = Math.max(score, 55 + term.length());
            }
        }
        for (String term : VIEW_TERMS) {
            if (normalized.contains(term)) {
                score = Math.max(score, 30 + term.length());
            }
        }
        return score;
    }

    @Override
    public SkillResult execute(SkillContext context) {
        String text = context.text() == null ? "" : context.text();
        String normalized = normalize(text);
        boolean cancel = CANCEL_TERMS.stream().anyMatch(normalized::contains);
        if (cancel) {
            Matcher matcher = INDEX.matcher(text);
            String lastIndex = null;
            while (matcher.find()) {
                lastIndex = matcher.group(1);
            }
            if (lastIndex == null) {
                return SkillResult.completed(
                        "请告诉我取消哪一条，例如：取消提醒 2（序号见“我的提醒”）。");
            }
            List<Reminder> reminders = reminderStore.list(context.userId());
            int index = Integer.parseInt(lastIndex);
            if (index < 1 || index > reminders.size()) {
                return SkillResult.completed("没有编号 " + index + " 的提醒，请用“我的提醒”查看当前列表。");
            }
            reminderStore.cancel(context.userId(), reminders.get(index - 1).id());
            return SkillResult.completed("已取消提醒：" + reminders.get(index - 1).content());
        }
        return SkillResult.completed(listReply(context.userId()));
    }

    private String listReply(String userId) {
        List<Reminder> reminders = reminderStore.list(userId);
        if (reminders.isEmpty()) {
            LOGGER.info("查看提醒 userId={} 列表为空", userId);
            return "你还没有设置任何提醒。可以告诉我“明天上午10点提醒我交作业”，我会帮你设置定时提醒。";
        }
        StringBuilder reply = new StringBuilder("你的提醒（共 ").append(reminders.size()).append(" 条）：\n");
        for (int index = 0; index < reminders.size(); index++) {
            Reminder reminder = reminders.get(index);
            String when = ZonedDateTime.ofInstant(
                    Instant.ofEpochMilli(reminder.atEpochMillis()), CHINA_ZONE).format(TIME_FORMAT);
            reply.append(index + 1).append(". ")
                    .append(when)
                    .append(reminder.repeatDaily() ? "（每天）" : "")
                    .append(" ").append(reminder.content()).append('\n');
        }
        reply.append("发送“取消提醒 序号”可删除。");
        return reply.toString();
    }

    private String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{S}\\s]+", "");
    }
}
