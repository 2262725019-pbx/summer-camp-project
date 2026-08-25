package com.summercamp.project.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.summercamp.project.llm.ChatMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AgentExecutorTest {
    @Test
    void executesLinearPlanInPlanOrder() {
        List<String> order = new ArrayList<>();
        FakeAgentActionHandler first = FakeAgentActionHandler.succeeding(AgentAction.GET_DATETIME, order);
        FakeAgentActionHandler second = FakeAgentActionHandler.succeeding(AgentAction.GET_WEATHER, order);
        FakeAgentActionHandler third = FakeAgentActionHandler.succeeding(AgentAction.SYNTHESIZE, order);
        AgentPlan plan = plan(
                step("S1", AgentAction.GET_DATETIME),
                step("S2", AgentAction.GET_WEATHER, "S1"),
                step("S3", AgentAction.SYNTHESIZE, "S2")
        );

        AgentState state = executor(first, second, third).execute(plan);

        assertEquals(List.of("S1", "S2", "S3"), order);
        assertEquals(Map.of(
                "S1", AgentStepStatus.COMPLETED,
                "S2", AgentStepStatus.COMPLETED,
                "S3", AgentStepStatus.COMPLETED
        ), state.statuses());
        assertTerminalStateConsistent(state);
    }

    @Test
    void executesBranchesDeterministicallyInOriginalPlanOrder() {
        List<String> order = new ArrayList<>();
        AgentPlan plan = plan(
                step("S1", AgentAction.GET_DATETIME),
                step("S2", AgentAction.GET_WEATHER, "S1"),
                step("S3", AgentAction.RETRIEVE_KNOWLEDGE, "S1"),
                step("S4", AgentAction.SYNTHESIZE, "S2", "S3")
        );

        AgentState state = executor(
                FakeAgentActionHandler.succeeding(AgentAction.GET_DATETIME, order),
                FakeAgentActionHandler.succeeding(AgentAction.GET_WEATHER, order),
                FakeAgentActionHandler.succeeding(AgentAction.RETRIEVE_KNOWLEDGE, order),
                FakeAgentActionHandler.succeeding(AgentAction.SYNTHESIZE, order)
        ).execute(plan);

        assertEquals(List.of("S1", "S2", "S3", "S4"), order);
        assertTrue(state.statuses().values().stream()
                .allMatch(status -> status == AgentStepStatus.COMPLETED));
    }

    @Test
    void handlerCanReadPreviousObservationFromState() {
        AtomicReference<String> chainedOutput = new AtomicReference<>();
        FakeAgentActionHandler weather = new FakeAgentActionHandler(
                AgentAction.GET_WEATHER,
                (step, context) -> new AgentObservation(
                        step.id(),
                        true,
                        "weather loaded",
                        Map.of("output", "weather-data")
                )
        );
        FakeAgentActionHandler synthesis = new FakeAgentActionHandler(
                AgentAction.SYNTHESIZE,
                (step, context) -> {
                    String output = context.state()
                            .findObservation("S1")
                            .orElseThrow()
                            .structuredData()
                            .get("output");
                    chainedOutput.set(output);
                    return new AgentObservation(step.id(), true, "used " + output);
                }
        );

        AgentState state = executor(weather, synthesis).execute(plan(
                step("S1", AgentAction.GET_WEATHER),
                step("S2", AgentAction.SYNTHESIZE, "S1")
        ));

        assertEquals("weather-data", chainedOutput.get());
        assertEquals("used weather-data", state.findObservation("S2").orElseThrow().summary());
    }

    @Test
    void failedStepSkipsItsDependents() {
        List<String> order = new ArrayList<>();
        FakeAgentActionHandler first = FakeAgentActionHandler.succeeding(AgentAction.GET_DATETIME, order);
        FakeAgentActionHandler failing = new FakeAgentActionHandler(
                AgentAction.GET_WEATHER,
                (step, context) -> {
                    order.add(step.id());
                    return new AgentObservation(step.id(), false, "safe failure");
                }
        );
        AgentPlan plan = plan(
                step("S1", AgentAction.GET_DATETIME),
                step("S2", AgentAction.GET_WEATHER, "S1"),
                step("S3", AgentAction.SYNTHESIZE, "S2")
        );

        AgentState state = executor(first, failing).execute(plan);

        assertEquals(List.of("S1", "S2"), order);
        assertEquals(AgentStepStatus.FAILED, state.statusOf("S2"));
        assertEquals(AgentStepStatus.SKIPPED, state.statusOf("S3"));
        assertFalse(state.findObservation("S2").orElseThrow().success());
        AgentObservation skipped = state.findObservation("S3").orElseThrow();
        assertFalse(skipped.success());
        assertTrue(skipped.summary().contains("dependency S2 ended as FAILED"));
        assertTerminalStateConsistent(state);
    }

    @Test
    void independentBranchContinuesAfterFailure() {
        List<String> order = new ArrayList<>();
        FakeAgentActionHandler failing = new FakeAgentActionHandler(
                AgentAction.GET_WEATHER,
                (step, context) -> {
                    order.add(step.id());
                    return new AgentObservation(step.id(), false, "safe failure");
                }
        );
        AgentPlan plan = plan(
                step("S1", AgentAction.GET_WEATHER),
                step("S2", AgentAction.SYNTHESIZE, "S1"),
                step("S3", AgentAction.RETRIEVE_KNOWLEDGE)
        );

        AgentState state = executor(
                failing,
                FakeAgentActionHandler.succeeding(AgentAction.RETRIEVE_KNOWLEDGE, order)
        ).execute(plan);

        assertEquals(List.of("S1", "S3"), order);
        assertEquals(AgentStepStatus.FAILED, state.statusOf("S1"));
        assertEquals(AgentStepStatus.SKIPPED, state.statusOf("S2"));
        assertEquals(AgentStepStatus.COMPLETED, state.statusOf("S3"));
    }

    @Test
    void missingHandlerFailsExecutionClearly() {
        AgentExecutionException exception = assertThrows(
                AgentExecutionException.class,
                () -> executor().execute(plan(step("S1", AgentAction.GET_WEATHER)))
        );

        assertEquals(AgentExecutionFailureReason.MISSING_HANDLER, exception.reason());
        assertTrue(exception.getMessage().contains("GET_WEATHER"));
    }

    @Test
    void cyclicRuntimeDependenciesFailWithNoProgressInsteadOfLooping() {
        AgentPlan plan = plan(
                step("S1", AgentAction.GET_DATETIME, "S2"),
                step("S2", AgentAction.GET_WEATHER, "S1")
        );

        AgentExecutionException exception = assertThrows(
                AgentExecutionException.class,
                () -> executor().execute(plan)
        );

        assertEquals(AgentExecutionFailureReason.NO_PROGRESS, exception.reason());
    }

    @Test
    void unknownRuntimeDependencyFailsClearly() {
        AgentPlan plan = plan(step("S1", AgentAction.GET_DATETIME, "missing"));

        AgentExecutionException exception = assertThrows(
                AgentExecutionException.class,
                () -> executor().execute(plan)
        );

        assertEquals(AgentExecutionFailureReason.INVALID_RUNTIME_DEPENDENCY_STATE, exception.reason());
    }

    @Test
    void executesEveryStepExactlyOnce() {
        List<String> order = new ArrayList<>();
        FakeAgentActionHandler handler = FakeAgentActionHandler.succeeding(AgentAction.CALCULATE, order);
        AgentPlan plan = plan(
                step("S1", AgentAction.CALCULATE),
                step("S2", AgentAction.CALCULATE, "S1"),
                step("S3", AgentAction.CALCULATE, "S2")
        );

        executor(handler).execute(plan);

        assertEquals(1, handler.invocationCount("S1"));
        assertEquals(1, handler.invocationCount("S2"));
        assertEquals(1, handler.invocationCount("S3"));
    }

    @Test
    void stepIsRunningWithoutTerminalObservationDuringHandlerExecution() {
        AtomicReference<AgentStepStatus> statusDuringExecution = new AtomicReference<>();
        AtomicBoolean observationPresentDuringExecution = new AtomicBoolean(true);
        FakeAgentActionHandler handler = new FakeAgentActionHandler(
                AgentAction.GET_DATETIME,
                (step, context) -> {
                    statusDuringExecution.set(context.state().statusOf(step.id()));
                    observationPresentDuringExecution.set(
                            context.state().findObservation(step.id()).isPresent()
                    );
                    return new AgentObservation(step.id(), true, "done");
                }
        );

        AgentState state = executor(handler).execute(plan(step("S1", AgentAction.GET_DATETIME)));

        assertEquals(AgentStepStatus.RUNNING, statusDuringExecution.get());
        assertFalse(observationPresentDuringExecution.get());
        assertEquals(AgentStepStatus.COMPLETED, state.statusOf("S1"));
        assertTrue(state.findObservation("S1").orElseThrow().success());
    }

    @Test
    void executionContextExposesImmutableCollectionsAndOriginalGoal() {
        AtomicReference<AgentExecutionContext> captured = new AtomicReference<>();
        FakeAgentActionHandler handler = new FakeAgentActionHandler(
                AgentAction.GET_DATETIME,
                (step, context) -> {
                    captured.set(context);
                    return new AgentObservation(step.id(), true, "ok");
                }
        );
        AgentPlan plan = plan(step("S1", AgentAction.GET_DATETIME));
        List<ChatMessage> history = new ArrayList<>(List.of(ChatMessage.user("previous")));

        executor(handler).execute("user-42", "original user goal", history, true, plan);
        history.clear();
        AgentExecutionContext context = captured.get();

        assertEquals("user-42", context.userId());
        assertEquals("original user goal", context.originalGoal());
        assertEquals(List.of(ChatMessage.user("previous")), context.history());
        assertTrue(context.voiceMessage());
        assertThrows(UnsupportedOperationException.class, () -> context.history().clear());
        assertThrows(UnsupportedOperationException.class, () -> context.plan().steps().clear());
        assertThrows(UnsupportedOperationException.class, () -> context.state().statuses().clear());
        assertThrows(UnsupportedOperationException.class, () -> context.state().observations().clear());
    }

    @Test
    void synthesisRunsOnlyAfterEveryDependencyCompletes() {
        List<String> order = new ArrayList<>();
        AtomicBoolean dependenciesCompleted = new AtomicBoolean();
        FakeAgentActionHandler synthesis = new FakeAgentActionHandler(
                AgentAction.SYNTHESIZE,
                (step, context) -> {
                    dependenciesCompleted.set(
                            context.state().statusOf("S1") == AgentStepStatus.COMPLETED
                                    && context.state().statusOf("S2") == AgentStepStatus.COMPLETED
                    );
                    order.add(step.id());
                    return new AgentObservation(step.id(), true, "synthesized");
                }
        );
        AgentPlan plan = plan(
                step("S1", AgentAction.GET_DATETIME),
                step("S2", AgentAction.GET_WEATHER),
                step("S3", AgentAction.SYNTHESIZE, "S1", "S2")
        );

        executor(
                FakeAgentActionHandler.succeeding(AgentAction.GET_DATETIME, order),
                FakeAgentActionHandler.succeeding(AgentAction.GET_WEATHER, order),
                synthesis
        ).execute(plan);

        assertEquals(List.of("S1", "S2", "S3"), order);
        assertTrue(dependenciesCompleted.get());
    }

    @Test
    void maximumExecutedStepGuardStopsThirteenthInvocation() {
        List<AgentStep> steps = new ArrayList<>();
        for (int index = 1; index <= AgentExecutor.MAX_EXECUTED_STEPS + 1; index++) {
            steps.add(step("S" + index, AgentAction.CALCULATE));
        }
        FakeAgentActionHandler handler = FakeAgentActionHandler.succeeding(
                AgentAction.CALCULATE,
                new ArrayList<>()
        );

        AgentExecutionException exception = assertThrows(
                AgentExecutionException.class,
                () -> executor(handler).execute(new AgentPlan("test goal", steps))
        );

        assertEquals(AgentExecutionFailureReason.MAX_EXECUTED_STEPS, exception.reason());
        assertEquals(AgentExecutor.MAX_EXECUTED_STEPS, handler.totalInvocationCount());
        assertEquals(0, handler.invocationCount("S13"));
    }

    private AgentExecutor executor(AgentActionHandler... handlers) {
        return new AgentExecutor(new AgentActionHandlerRegistry(List.of(handlers)));
    }

    private AgentPlan plan(AgentStep... steps) {
        return new AgentPlan("test goal", List.of(steps));
    }

    private AgentStep step(String id, AgentAction action, String... dependencies) {
        return new AgentStep(id, action, "execute " + id, "test", List.of(dependencies));
    }

    private void assertTerminalStateConsistent(AgentState state) {
        for (Map.Entry<String, AgentStepStatus> entry : state.statuses().entrySet()) {
            AgentObservation observation = state.findObservation(entry.getKey()).orElseThrow();
            if (entry.getValue() == AgentStepStatus.COMPLETED) {
                assertTrue(observation.success());
            } else if (entry.getValue() == AgentStepStatus.FAILED
                    || entry.getValue() == AgentStepStatus.SKIPPED) {
                assertFalse(observation.success());
            }
        }
    }
}
