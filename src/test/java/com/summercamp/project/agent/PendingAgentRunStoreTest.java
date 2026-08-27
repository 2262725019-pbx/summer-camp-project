package com.summercamp.project.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PendingAgentRunStoreTest {

    @Test
    void storesOneCheckpointPerUserAndIsolatesUsers() {
        PendingAgentRunStore store = new PendingAgentRunStore();
        AgentRunRequest request = request("goal-a");

        assertTrue(store.rememberInitial("user-a", request, waitingResult("goal-a")).isPresent());

        assertTrue(store.get("user-a").isPresent());
        assertTrue(store.get("user-b").isEmpty());
        assertEquals("goal-a", store.get("user-a").orElseThrow().originalGoal());
        store.clear("user-a");
        assertTrue(store.get("user-a").isEmpty());
    }

    @Test
    void expiredGetDeletesCheckpointAndResumeRefreshesTtl() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-27T06:00:00Z"));
        PendingAgentRunStore store = new PendingAgentRunStore(clock);
        AgentRunCheckpoint first = store.rememberInitial(
                "user-a", request("goal-a"), waitingResult("goal-a")).orElseThrow();

        clock.advance(Duration.ofMinutes(10));
        AgentRunCheckpoint refreshed = store.rememberResumed(
                "user-a", first, waitingResult("goal-a")).orElseThrow();
        assertEquals(1, refreshed.resumeAttemptCount());
        assertEquals(clock.instant().plus(PendingAgentRunStore.TTL),
                store.get("user-a").orElseThrow().expiresAt());

        clock.advance(Duration.ofMinutes(15));
        assertTrue(store.get("user-a").isEmpty());
        assertTrue(store.get("user-a").isEmpty());
    }

    @Test
    void replacesExistingCheckpointForSameUser() {
        PendingAgentRunStore store = new PendingAgentRunStore();
        store.rememberInitial("user-a", request("goal-a"), waitingResult("goal-a"));
        store.rememberInitial("user-a", request("goal-b"), waitingResult("goal-b"));

        assertEquals("goal-b", store.get("user-a").orElseThrow().originalGoal());
    }

    @Test
    void refusesProviderFailureAndAmbiguousWaitingState() {
        PendingAgentRunStore store = new PendingAgentRunStore();
        AgentRunResult failed = new AgentRunResult(
                AgentRunResult.Status.FAILED, "失败", null, null);
        assertTrue(store.rememberInitial("user-a", request("goal"), failed).isEmpty());

        AgentPlan plan = new AgentPlan("goal", List.of(
                step("meal-a", AgentAction.RUN_MEAL_SKILL),
                step("meal-b", AgentAction.RUN_MEAL_SKILL)));
        AgentState state = new AgentState(plan);
        state.recordObservation(waiting("meal-a"));
        state.recordObservation(waiting("meal-b"));
        AgentRunResult ambiguous = new AgentRunResult(
                AgentRunResult.Status.NEEDS_USER_INPUT, "补资料", plan, state);
        assertTrue(store.rememberInitial("user-a", request("goal"), ambiguous).isEmpty());
        assertTrue(store.get("user-a").isEmpty());
    }

    @Test
    void refusesCheckpointContainingRunningStep() {
        PendingAgentRunStore store = new PendingAgentRunStore();
        AgentPlan plan = new AgentPlan("goal", List.of(
                step("datetime", AgentAction.GET_DATETIME),
                step("meal", AgentAction.RUN_MEAL_SKILL)));
        AgentState state = new AgentState(plan);
        state.markRunning("datetime");
        state.recordObservation(waiting("meal"));
        AgentRunResult corrupted = new AgentRunResult(
                AgentRunResult.Status.NEEDS_USER_INPUT, "请补资料", plan, state);

        assertTrue(store.rememberInitial("user-a", request("goal"), corrupted).isEmpty());
        assertTrue(store.get("user-a").isEmpty());
    }

    @Test
    void checkpointExposesOnlyImmutablePlanAndStateSnapshots() {
        PendingAgentRunStore store = new PendingAgentRunStore();
        AgentRunCheckpoint checkpoint = store.rememberInitial(
                "user-a", request("goal"), waitingResult("goal")).orElseThrow();

        assertTrue(checkpoint.state() instanceof AgentStateSnapshot);
        assertFalse(checkpoint.originalGoal().contains("Authorization"));
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> checkpoint.state().statuses().clear());
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> checkpoint.plan().steps().clear());
    }

    private AgentRunRequest request(String goal) {
        return new AgentRunRequest("user-a", goal, List.of(), false);
    }

    private AgentRunResult waitingResult(String goal) {
        AgentPlan plan = new AgentPlan(goal, List.of(
                step("datetime", AgentAction.GET_DATETIME),
                new AgentStep("meal", AgentAction.RUN_MEAL_SKILL,
                        "饮食", "资料", List.of("datetime")),
                new AgentStep("validate", AgentAction.VALIDATE,
                        "校验", "闭环", List.of("meal"))));
        AgentState state = new AgentState(plan);
        state.recordObservation(new AgentObservation("datetime", true, "日期完成"));
        state.recordObservation(waiting("meal"));
        state.markSkipped("validate", "waiting dependency");
        return new AgentRunResult(
                AgentRunResult.Status.NEEDS_USER_INPUT, "请补资料", plan, state);
    }

    private AgentObservation waiting(String id) {
        return new AgentObservation(id, false, "请补资料", Map.of(
                "code", "NEEDS_USER_INPUT", "recoverable", "true"));
    }

    private AgentStep step(String id, AgentAction action) {
        return new AgentStep(id, action, "执行", "测试", List.of());
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
