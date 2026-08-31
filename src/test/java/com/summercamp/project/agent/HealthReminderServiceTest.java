package com.summercamp.project.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.summercamp.project.agent.artifact.HealthPlanArtifact;
import com.summercamp.project.agent.model.HealthGoal;
import com.summercamp.project.agent.model.HealthGoalType;
import com.summercamp.project.agent.store.CompletedHealthPlanStore;
import com.summercamp.project.agent.store.AgentStateDatabase;
import com.summercamp.project.config.AgentPersistenceProperties;
import com.summercamp.project.config.HealthReminderProperties;
import com.summercamp.project.wechat.InboundMessage;
import com.summercamp.project.wechat.WechatGateway;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HealthReminderServiceTest {

    @Test
    void sendsOneReminderPerDayAndCanBeDisabledByTheUser() {
        Instant now = Instant.parse("2026-08-27T00:00:00Z");
        Clock clock = Clock.fixed(now, ZoneId.of("UTC"));
        HealthReminderProperties properties = new HealthReminderProperties(
                true, Duration.ofSeconds(30), Duration.ofDays(14), "Asia/Shanghai");
        CompletedHealthPlanStore plans = new CompletedHealthPlanStore(properties, clock);
        plans.save("user", goal(), new HealthPlanArtifact(
                "七日计划", "第1天：上肢训练；按训练日餐单执行。\n第2天：恢复日。", List.of(), List.of()));
        FakeGateway gateway = new FakeGateway();
        HealthReminderService service = new HealthReminderService(properties, plans, gateway, clock);

        assertThat(service.handleCommand("user", "开启每日健康提醒 07:30"))
                .hasValueSatisfying(reply -> assertThat(reply).contains("07:30"));
        service.dispatchDue(Instant.parse("2026-08-27T00:01:00Z"));
        service.dispatchDue(Instant.parse("2026-08-27T01:00:00Z"));

        assertThat(gateway.texts).singleElement().asString().contains("第1天：上肢训练");
        assertThat(service.handleCommand("user", "关闭健康提醒")).isPresent();
        service.dispatchDue(Instant.parse("2026-08-28T01:00:00Z"));
        assertThat(gateway.texts).hasSize(1);
    }

    @Test
    void rejectsInvalidTimesAndRemovesFinishedPlanSubscriptions() {
        Instant now = Instant.parse("2026-08-27T00:00:00Z");
        Clock clock = Clock.fixed(now, ZoneId.of("UTC"));
        HealthReminderProperties properties = new HealthReminderProperties(
                true, Duration.ofSeconds(30), Duration.ofDays(14), "Asia/Shanghai");
        CompletedHealthPlanStore plans = new CompletedHealthPlanStore(properties, clock);
        HealthGoal oneDayGoal = new HealthGoal(HealthGoalType.HEALTHY_ROUTINE, 1, "男", 20, 175.0, 70.0,
                "上海", 4, 60, 4, "中度", true, true, List.of(), "test");
        plans.save("user", oneDayGoal, new HealthPlanArtifact(
                "一天计划", "第1天：规律作息。", List.of(), List.of()));
        HealthReminderService service = new HealthReminderService(properties, plans, new FakeGateway(), clock);

        assertThat(service.handleCommand("user", "开启每日健康提醒 25:70").orElseThrow())
                .contains("格式不正确");
        service.handleCommand("user", "开启每日健康提醒 07:30");
        service.dispatchDue(Instant.parse("2026-08-28T01:00:00Z"));

        assertThat(service.handleCommand("user", "查看健康提醒").orElseThrow())
                .isEqualTo("当前没有开启健康提醒。");
    }

    @Test
    void restoresReminderSubscriptionAfterServiceRestart(@TempDir Path temporaryDirectory) {
        Instant now = Instant.parse("2026-08-27T00:00:00Z");
        Clock clock = Clock.fixed(now, ZoneId.of("UTC"));
        HealthReminderProperties properties = new HealthReminderProperties(
                true, Duration.ofSeconds(30), Duration.ofDays(14), "Asia/Shanghai");
        AgentPersistenceProperties persistence = new AgentPersistenceProperties(
                true, temporaryDirectory.resolve("agent-state.db").toString());
        AgentStateDatabase firstDatabase = new AgentStateDatabase(persistence);
        CompletedHealthPlanStore plans = new CompletedHealthPlanStore(properties, clock);
        HealthReminderService first = new HealthReminderService(
                properties, plans, new FakeGateway(), clock, firstDatabase);
        first.handleCommand("user", "开启每日健康提醒 07:30");

        HealthReminderService restarted = new HealthReminderService(
                properties, plans, new FakeGateway(), clock, new AgentStateDatabase(persistence));

        assertThat(restarted.handleCommand("user", "查看健康提醒").orElseThrow())
                .contains("07:30");
    }

    private HealthGoal goal() {
        return new HealthGoal(HealthGoalType.MUSCLE_GAIN, 7, "男", 20, 175.0, 70.0,
                "上海", 4, 60, 4, "中度", true, true, List.of(), "test");
    }

    private static final class FakeGateway implements WechatGateway {
        private final List<String> texts = new ArrayList<>();

        @Override public void loginAndWait(Path qrCodePath) { }
        @Override public List<InboundMessage> poll() { return List.of(); }
        @Override public void sendText(String userId, String text) { texts.add(text); }
        @Override public void sendImage(String userId, byte[] data, String fileName, String caption) { }
        @Override public void sendVoice(String userId, byte[] data, String fileName, int durationMillis,
                int sampleRate, int encodeType, int bitsPerSample, String transcript) throws IOException { }
        @Override public void close() { }
    }
}
