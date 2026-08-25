package com.summercamp.project.agent.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.summercamp.project.agent.model.AgentRun;
import com.summercamp.project.agent.model.HealthGoal;
import com.summercamp.project.agent.model.HealthGoalType;
import com.summercamp.project.agent.model.StepStatus;
import com.summercamp.project.agent.planning.TaskPlanner;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TaskSchedulerTest {

    private final TaskScheduler scheduler = new TaskScheduler();

    @Test
    void retriesAFailedStepOnce() {
        AgentRun run = run();
        completeInitialSteps(run);
        AtomicInteger attempts = new AtomicInteger();

        String value = scheduler.execute(run, "retrieve-health-knowledge", () -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("temporary");
            }
            return "ok";
        });

        assertThat(value).isEqualTo("ok");
        assertThat(run.state("retrieve-health-knowledge").status()).isEqualTo(StepStatus.SUCCEEDED);
        assertThat(run.state("retrieve-health-knowledge").attempts()).isEqualTo(2);
    }

    @Test
    void independentStepsRunConcurrently() {
        AgentRun run = run();
        completeInitialSteps(run);
        AtomicInteger running = new AtomicInteger();
        AtomicBoolean overlapped = new AtomicBoolean();
        Map<String, java.util.function.Supplier<?>> actions = new LinkedHashMap<>();
        actions.put("retrieve-health-knowledge", () -> slowOperation(running, overlapped));
        actions.put("query-weather", () -> slowOperation(running, overlapped));
        actions.put("calculate-nutrition", () -> slowOperation(running, overlapped));

        Map<String, TaskScheduler.StepResult<Object>> results = scheduler.executeParallel(run, actions);

        assertThat(results.values()).allMatch(TaskScheduler.StepResult::succeeded);
        assertThat(overlapped).isTrue();
    }

    private String slowOperation(AtomicInteger running, AtomicBoolean overlapped) {
        if (running.incrementAndGet() > 1) {
            overlapped.set(true);
        }
        try {
            Thread.sleep(80);
            return "ok";
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        } finally {
            running.decrementAndGet();
        }
    }

    private void completeInitialSteps(AgentRun run) {
        run.start("parse-goal");
        run.succeed("parse-goal", run.goal());
        run.start("validate-goal");
        run.succeed("validate-goal", "valid");
    }

    private AgentRun run() {
        HealthGoal goal = new HealthGoal(
                HealthGoalType.MUSCLE_GAIN, 7, "男", 20, 175.0, 70.0, "上海",
                4, 60, 4, "中度", true, true, List.of(), "goal");
        Instant now = Instant.parse("2026-08-25T00:00:00Z");
        return new AgentRun("run-1", "user-1", goal, new TaskPlanner().createHealthPlan(),
                now, now.plus(Duration.ofMinutes(30)));
    }
}
