package com.summercamp.project.agent.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summercamp.project.agent.artifact.HealthPlanArtifact;
import com.summercamp.project.agent.evaluation.EvaluationReport;
import com.summercamp.project.agent.model.AgentRun;
import com.summercamp.project.agent.model.HealthGoal;
import com.summercamp.project.agent.model.HealthGoalType;
import com.summercamp.project.agent.model.StepStatus;
import com.summercamp.project.agent.planning.TaskPlanner;
import com.summercamp.project.config.AgentPersistenceProperties;
import com.summercamp.project.config.HealthReminderProperties;
import com.summercamp.project.rag.RagContext;
import com.summercamp.project.rag.RagDocument;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteAgentPersistenceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void restoresRunStatesAndTypedOutputsAfterStoreRestart() {
        Instant now = Instant.parse("2026-08-29T10:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        AgentStateDatabase database = database();
        SqliteAgentRunStore firstStore = new SqliteAgentRunStore(database, mapper, clock);
        AgentRun run = new AgentRun(
                "run-1", "user-1", goal("full original message"),
                new TaskPlanner().createHealthPlan(), now, now.plus(Duration.ofHours(1)));

        firstStore.save(run);
        run.start("parse-goal");
        run.succeed("parse-goal", run.goal());
        run.start("validate-goal");
        run.succeed("validate-goal", "valid");
        run.start("retrieve-health-knowledge");
        run.succeed("retrieve-health-knowledge", new RagContext(List.of(new RagContext.Hit(
                new RagDocument("health", "健康资料", List.of("健康"), "参考内容"), 4)), "参考内容"));
        run.start("query-weather");
        run.fail("query-weather", new IllegalStateException("temporary"));
        HealthPlanArtifact artifact = new HealthPlanArtifact(
                "七日计划", "第1天：训练。", List.of("health"), List.of());
        run.start("assemble-daily-schedule");
        run.succeed("assemble-daily-schedule", artifact);
        run.start("evaluate-plan");
        run.succeed("evaluate-plan", new EvaluationReport(true, List.of()));

        SqliteAgentRunStore restartedStore = new SqliteAgentRunStore(database(), mapper, clock);
        AgentRun restored = restartedStore.latest("user-1").orElseThrow();

        assertThat(restored.goal().sourceText()).isEmpty();
        assertThat(restored.state("retrieve-health-knowledge").status()).isEqualTo(StepStatus.SUCCEEDED);
        assertThat(restored.output("retrieve-health-knowledge")).isInstanceOf(RagContext.class);
        assertThat(restored.output("assemble-daily-schedule")).isInstanceOf(HealthPlanArtifact.class);
        assertThat(restored.output("evaluate-plan")).isInstanceOf(EvaluationReport.class);
        assertThat(restored.state("query-weather").status()).isEqualTo(StepStatus.FAILED);

        restored.prepareForResume();
        AgentRun savedAgain = new SqliteAgentRunStore(database(), mapper, clock)
                .latest("user-1").orElseThrow();
        assertThat(savedAgain.state("query-weather").status()).isEqualTo(StepStatus.PENDING);
    }

    @Test
    void restoresCompletedPlanWithoutPersistingTheRawSourceMessage() {
        Instant now = Instant.parse("2026-08-29T10:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        HealthReminderProperties properties = new HealthReminderProperties(
                true, Duration.ofSeconds(30), Duration.ofDays(14), "Asia/Shanghai");
        CompletedHealthPlanStore first = new CompletedHealthPlanStore(
                properties, clock, database(), mapper);
        first.save("user-2", goal("private source text"), new HealthPlanArtifact(
                "七日计划", "第1天：训练。", List.of("health"), List.of()));

        CompletedHealthPlanStore restarted = new CompletedHealthPlanStore(
                properties, clock, database(), mapper);

        assertThat(restarted.latest("user-2")).hasValueSatisfying(plan -> {
            assertThat(plan.goal().sourceText()).isEmpty();
            assertThat(plan.artifact().title()).isEqualTo("七日计划");
        });
    }

    private AgentStateDatabase database() {
        return new AgentStateDatabase(new AgentPersistenceProperties(
                true, temporaryDirectory.resolve("agent-state.db").toString()));
    }

    private HealthGoal goal(String source) {
        return new HealthGoal(
                HealthGoalType.MUSCLE_GAIN, 7, "男", 20, 175.0, 70.0, "上海",
                4, 60, 4, "中度", true, true, List.of(), source);
    }
}
