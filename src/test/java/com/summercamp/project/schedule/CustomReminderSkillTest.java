package com.summercamp.project.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summercamp.project.skill.SkillContext;
import com.summercamp.project.skill.SkillResult;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CustomReminderSkillTest {

    @TempDir
    Path tempDir;

    private CustomReminderSkill skill() {
        return new CustomReminderSkill(new ReminderStore(new ObjectMapper(), tempDir.resolve("r.json").toString()));
    }

    @Test
    void shouldMatchManageCommandsButNotSettingRequests() {
        CustomReminderSkill skill = skill();
        assertTrue(skill.matchScore("我的提醒") > 0);
        assertTrue(skill.matchScore("取消提醒 2") > 0);
        // 设置提醒是模型工具场景，不应被本 Skill 拦截
        assertEquals(0, skill.matchScore("明天上午10点提醒我交作业"));
        assertEquals(0, skill.matchScore("每天下午3点提醒我喝水"));
    }

    @Test
    void shouldListRemindersWithIndex() {
        CustomReminderSkill skill = skill();
        ReminderStore store = new ReminderStore(new ObjectMapper(), tempDir.resolve("r.json").toString());
        skill = new CustomReminderSkill(store);
        store.add("user-a", System.currentTimeMillis() + 60_000, "交作业", false);

        SkillResult result = skill.execute(context("我的提醒"));

        assertTrue(result.reply().contains("1."));
        assertTrue(result.reply().contains("交作业"));
    }

    @Test
    void shouldShowEmptyHint() {
        SkillResult result = skill().execute(context("我的提醒"));

        assertTrue(result.reply().contains("还没有设置任何提醒"));
    }

    @Test
    void shouldCancelByIndex() {
        ReminderStore store = new ReminderStore(new ObjectMapper(), tempDir.resolve("r.json").toString());
        store.add("user-a", System.currentTimeMillis() + 60_000, "交作业", false);
        CustomReminderSkill skill = new CustomReminderSkill(store);

        SkillResult result = skill.execute(context("取消提醒 1"));

        assertTrue(result.reply().contains("已取消提醒"));
        assertTrue(store.list("user-a").isEmpty());
    }

    @Test
    void shouldRejectOutOfRangeIndex() {
        ReminderStore store = new ReminderStore(new ObjectMapper(), tempDir.resolve("r.json").toString());
        store.add("user-a", System.currentTimeMillis() + 60_000, "交作业", false);
        CustomReminderSkill skill = new CustomReminderSkill(store);

        SkillResult result = skill.execute(context("取消提醒 9"));

        assertFalse(result.reply().contains("已取消"));
        assertTrue(store.list("user-a").size() == 1);
    }

    private SkillContext context(String text) {
        return new SkillContext("user-a", text, List.of(), false);
    }
}
