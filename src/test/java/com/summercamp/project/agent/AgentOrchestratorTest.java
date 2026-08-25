package com.summercamp.project.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class AgentOrchestratorTest {
    @Test
    void completesHappyPathWithValidateBeforeSynthesis() {
        AgentPlan plan = plan("制定今日健康生活计划");
        List<String> order = new ArrayList<>();
        AtomicInteger synthesisCalls = new AtomicInteger();
        AgentOrchestrator orchestrator = orchestrator(
                plan,
                order,
                synthesisCalls,
                step -> successFor(step, false)
        );

        AgentRunResult result = orchestrator.run(
                new AgentRunRequest("user-a", plan.goal(), List.of(), false));

        assertEquals(AgentRunResult.Status.COMPLETED, result.status());
        assertEquals(List.of("datetime", "weather", "rag", "validate", "synthesis"), order);
        assertEquals(1, synthesisCalls.get());
        assertTrue(result.reply().contains("最终健康计划"));
        assertTrue(result.state() instanceof AgentStateSnapshot);
    }

    @Test
    void recoverableFailureReturnsNeedsUserInputAndDoesNotSynthesize() {
        AgentPlan plan = plan("制定今日健康生活计划");
        AtomicInteger synthesisCalls = new AtomicInteger();
        AgentOrchestrator orchestrator = orchestrator(
                plan,
                new ArrayList<>(),
                synthesisCalls,
                step -> step.action() == AgentAction.GET_WEATHER
                        ? new AgentObservation(step.id(), false, "请补充所在城市",
                                Map.of("code", "NEEDS_USER_INPUT", "recoverable", "true"))
                        : successFor(step, false)
        );

        AgentRunResult result = orchestrator.run(
                new AgentRunRequest("user-a", plan.goal(), List.of(), false));

        assertEquals(AgentRunResult.Status.NEEDS_USER_INPUT, result.status());
        assertEquals(0, synthesisCalls.get());
        assertEquals(AgentStepStatus.SKIPPED, result.state().statusOf("synthesis"));
    }

    @Test
    void nonRecoverableWeatherFailureReturnsFailedAndDoesNotSynthesize() {
        AgentPlan plan = plan("制定今日健康生活计划");
        AtomicInteger synthesisCalls = new AtomicInteger();
        AgentOrchestrator orchestrator = orchestrator(
                plan,
                new ArrayList<>(),
                synthesisCalls,
                step -> step.action() == AgentAction.GET_WEATHER
                        ? new AgentObservation(step.id(), false, "天气服务失败", Map.of("code", "WEATHER_FAILED"))
                        : successFor(step, false)
        );

        AgentRunResult result = orchestrator.run(
                new AgentRunRequest("user-a", plan.goal(), List.of(), false));

        assertEquals(AgentRunResult.Status.FAILED, result.status());
        assertEquals(0, synthesisCalls.get());
        assertEquals(AgentStepStatus.FAILED, result.state().statusOf("weather"));
    }

    @Test
    void ragMissIsACompletedObservationAndStillSynthesizes() {
        AgentPlan plan = plan("制定今日健康生活计划");
        AtomicInteger synthesisCalls = new AtomicInteger();
        AgentOrchestrator orchestrator = orchestrator(
                plan,
                new ArrayList<>(),
                synthesisCalls,
                step -> successFor(step, true)
        );

        AgentRunResult result = orchestrator.run(
                new AgentRunRequest("user-a", plan.goal(), List.of(), false));

        assertEquals(AgentRunResult.Status.COMPLETED, result.status());
        assertEquals(1, synthesisCalls.get());
        assertEquals("false", result.state().findObservation("rag").orElseThrow()
                .structuredData().get("matched"));
    }

    private AgentOrchestrator orchestrator(
            AgentPlan plan,
            List<String> order,
            AtomicInteger synthesisCalls,
            Function<AgentStep, AgentObservation> businessResult
    ) {
        List<AgentActionHandler> handlers = new ArrayList<>();
        handlers.add(recording(AgentAction.GET_DATETIME, order, businessResult));
        handlers.add(recording(AgentAction.GET_WEATHER, order, businessResult));
        handlers.add(recording(AgentAction.RETRIEVE_KNOWLEDGE, order, businessResult));

        ValidateActionHandler validation = new ValidateActionHandler();
        handlers.add(recording(AgentAction.VALIDATE, order, step -> validation.execute(
                step, currentContext.get())));
        SynthesizeActionHandler synthesis = new SynthesizeActionHandler(
                new AgentSynthesisContextBuilder(),
                (goal, context) -> {
                    synthesisCalls.incrementAndGet();
                    return "最终健康计划：按真实结果执行。";
                });
        handlers.add(recording(AgentAction.SYNTHESIZE, order, step -> synthesis.execute(
                step, currentContext.get())));

        AgentActionHandlerRegistry registry = new AgentActionHandlerRegistry(handlers.stream()
                .map(handler -> contextual(handler))
                .toList());
        return new AgentOrchestrator(goal -> plan, new AgentExecutor(registry));
    }

    private final ThreadLocal<AgentExecutionContext> currentContext = new ThreadLocal<>();

    private AgentActionHandler contextual(AgentActionHandler delegate) {
        return new AgentActionHandler() {
            @Override
            public AgentAction action() {
                return delegate.action();
            }

            @Override
            public AgentObservation execute(AgentStep step, AgentExecutionContext context) {
                currentContext.set(context);
                try {
                    return delegate.execute(step, context);
                } finally {
                    currentContext.remove();
                }
            }
        };
    }

    private AgentActionHandler recording(
            AgentAction action,
            List<String> order,
            Function<AgentStep, AgentObservation> result
    ) {
        return new AgentActionHandler() {
            @Override
            public AgentAction action() {
                return action;
            }

            @Override
            public AgentObservation execute(AgentStep step, AgentExecutionContext context) {
                order.add(step.id());
                return result.apply(step);
            }
        };
    }

    private AgentObservation successFor(AgentStep step, boolean ragMiss) {
        if (step.action() == AgentAction.RETRIEVE_KNOWLEDGE) {
            return new AgentObservation(
                    step.id(), true, ragMiss ? "未检索到匹配知识" : "检索到本地知识",
                    Map.of("matched", Boolean.toString(!ragMiss)));
        }
        return new AgentObservation(step.id(), true, step.id() + " 成功");
    }

    private AgentPlan plan(String goal) {
        return new AgentPlan(goal, List.of(
                step("datetime", AgentAction.GET_DATETIME, List.of(), Map.of()),
                step("weather", AgentAction.GET_WEATHER, List.of("datetime"),
                        Map.of("location", "镇江", "period", "TODAY")),
                step("rag", AgentAction.RETRIEVE_KNOWLEDGE, List.of(), Map.of("query", "健康生活")),
                step("validate", AgentAction.VALIDATE, List.of("weather", "rag"), Map.of()),
                step("synthesis", AgentAction.SYNTHESIZE, List.of("validate"), Map.of())
        ));
    }

    private AgentStep step(
            String id,
            AgentAction action,
            List<String> dependencies,
            Map<String, String> inputs
    ) {
        return new AgentStep(id, action, "执行 " + id, "满足目标", dependencies, inputs);
    }
}
