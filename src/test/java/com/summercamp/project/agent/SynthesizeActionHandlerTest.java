package com.summercamp.project.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.summercamp.project.rag.RagRetriever;
import com.summercamp.project.llm.AgentProviderException;
import com.summercamp.project.llm.AgentProviderFailureCategory;
import com.summercamp.project.skill.SkillRegistry;
import com.summercamp.project.tool.ToolRegistry;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SynthesizeActionHandlerTest {
    @Test
    void synthesizesOnlyAfterSuccessfulValidationWithoutCapabilityRegistries() {
        AgentPlan plan = plan();
        AgentState state = validatedState(plan);
        AtomicInteger clientCalls = new AtomicInteger();
        AgentSynthesisClient client = (goal, context) -> {
            clientCalls.incrementAndGet();
            assertTrue(context.contains("真实天气"));
            return AgentSynthesisResult.answerOnly("这是基于真实结果生成的健康计划。");
        };
        ToolRegistry tools = mock(ToolRegistry.class);
        SkillRegistry skills = mock(SkillRegistry.class);
        RagRetriever rag = mock(RagRetriever.class);

        AgentObservation result = new SynthesizeActionHandler(
                new AgentSynthesisContextBuilder(), client).execute(
                plan.steps().getLast(), new AgentExecutionContext(plan.goal(), state, plan));

        assertTrue(result.success());
        assertEquals(SynthesizeActionHandler.SYNTHESIS_COMPLETED, result.structuredData().get("code"));
        assertEquals(1, clientCalls.get());
        verifyNoInteractions(tools, skills, rag);
    }

    @Test
    void blocksClientWhenValidationIsMissingOrFailed() {
        AgentPlan plan = plan();
        AgentState state = new AgentState(plan);
        state.recordObservation(new AgentObservation("datetime", true, "日期"));
        state.recordObservation(new AgentObservation("weather", true, "真实天气"));
        state.recordObservation(new AgentObservation("rag", true, "未匹配", Map.of("matched", "false")));
        state.recordObservation(new AgentObservation(
                "validate", false, "校验失败", Map.of("code", ValidateActionHandler.VALIDATION_FAILED)));
        AtomicInteger calls = new AtomicInteger();

        AgentObservation result = new SynthesizeActionHandler(
                new AgentSynthesisContextBuilder(), (goal, context) -> {
                    calls.incrementAndGet();
                    return AgentSynthesisResult.answerOnly("不应调用");
                }).execute(plan.steps().getLast(), new AgentExecutionContext(plan.goal(), state, plan));

        assertFalse(result.success());
        assertEquals(0, calls.get());
    }

    @Test
    void convertsBlankOrClientFailureToSafeFailure() {
        AgentPlan plan = plan();
        AgentState state = validatedState(plan);

        AgentObservation blank = new SynthesizeActionHandler(
                new AgentSynthesisContextBuilder(), (goal, context) ->
                        AgentSynthesisResult.invalid(
                                AgentSynthesisParseError.MALFORMED_JSON)).execute(
                plan.steps().getLast(), new AgentExecutionContext(plan.goal(), state, plan));
        AgentObservation failed = new SynthesizeActionHandler(
                new AgentSynthesisContextBuilder(), (goal, context) -> {
                    throw new IllegalStateException("provider stack and key");
                }).execute(plan.steps().getLast(), new AgentExecutionContext(plan.goal(), state, plan));

        assertFalse(blank.success());
        assertFalse(failed.success());
        assertFalse(failed.summary().contains("provider"));
    }

    @Test
    void missingRequiredExerciseResultFailsValidationAndBlocksSynthesisClient() {
        AgentPlan plan = new AgentPlan("未来7天需要运动安排", List.of(
                step("datetime", AgentAction.GET_DATETIME, List.of()),
                new AgentStep(
                        "weather", AgentAction.GET_WEATHER, "执行", "原因", List.of("datetime"),
                        Map.of("location", "镇江", "period", "THREE_DAYS")),
                new AgentStep(
                        "rag", AgentAction.RETRIEVE_KNOWLEDGE, "执行", "原因", List.of(),
                        Map.of("query", "健康生活")),
                step("validate", AgentAction.VALIDATE, List.of("weather", "rag")),
                step("synthesis", AgentAction.SYNTHESIZE, List.of("validate"))
        ));
        AgentState state = new AgentState(plan);
        state.recordObservation(new AgentObservation("datetime", true, "日期"));
        state.recordObservation(new AgentObservation("weather", true, "三日天气"));
        state.recordObservation(new AgentObservation("rag", true, "健康知识"));
        AgentExecutionContext context = new AgentExecutionContext(plan.goal(), state, plan);
        AgentObservation validation = new ValidateActionHandler()
                .execute(plan.steps().get(3), context);
        state.recordObservation(validation);
        AtomicInteger calls = new AtomicInteger();

        AgentObservation synthesis = new SynthesizeActionHandler(
                new AgentSynthesisContextBuilder(), (goal, grounding) -> {
                    calls.incrementAndGet();
                    return AgentSynthesisResult.answerOnly("不应调用");
                }).execute(plan.steps().getLast(), context);

        assertFalse(validation.success());
        assertEquals(
                ValidateActionHandler.MISSING_REQUIRED_EXERCISE_RESULT,
                validation.structuredData().get("code")
        );
        assertFalse(synthesis.success());
        assertEquals(0, calls.get());
    }

    @Test
    void failedRequiredDatetimeResultBlocksSynthesisClient() {
        AgentPlan plan = new AgentPlan("未来7天健康生活规划", List.of(
                step("datetime", AgentAction.GET_DATETIME, List.of()),
                new AgentStep(
                        "rag", AgentAction.RETRIEVE_KNOWLEDGE, "执行", "原因", List.of(),
                        Map.of("query", "健康生活")),
                new AgentStep(
                        "calculate", AgentAction.CALCULATE, "执行", "原因", List.of(),
                        Map.of("expression", "7 * 24")),
                step("validate", AgentAction.VALIDATE, List.of("datetime", "rag", "calculate")),
                step("synthesis", AgentAction.SYNTHESIZE, List.of("validate"))
        ));
        AgentState state = new AgentState(plan);
        state.recordObservation(new AgentObservation("datetime", false, "日期工具失败"));
        state.recordObservation(new AgentObservation("rag", true, "健康知识"));
        state.recordObservation(new AgentObservation("calculate", true, "168"));
        AgentExecutionContext context = new AgentExecutionContext(plan.goal(), state, plan);
        AgentObservation validation = new ValidateActionHandler()
                .execute(plan.steps().get(3), context);
        state.recordObservation(validation);
        AtomicInteger calls = new AtomicInteger();

        AgentObservation synthesis = new SynthesizeActionHandler(
                new AgentSynthesisContextBuilder(), (goal, grounding) -> {
                    calls.incrementAndGet();
                    return AgentSynthesisResult.answerOnly("不应调用");
                }).execute(plan.steps().getLast(), context);

        assertFalse(validation.success());
        assertEquals(
                ValidateActionHandler.MISSING_REQUIRED_DATETIME_RESULT,
                validation.structuredData().get("code")
        );
        assertFalse(synthesis.success());
        assertEquals(0, calls.get());
    }

    @Test
    void repairsInvalidSynthesisOnceAndRecordsContentFreeMetrics() {
        AgentPlan plan = sevenDayPlan();
        AgentState state = validatedSevenDayState(plan);
        AgentRunMetricsCollector collector = new AgentRunMetricsCollector();
        AtomicInteger calls = new AtomicInteger();
        List<String> synthesisContexts = new ArrayList<>();
        AgentSynthesisClient client = (goal, grounding) -> {
            synthesisContexts.add(grounding);
            String answer = calls.getAndIncrement() == 0
                    ? sevenDayAnswer().replace("8月31日（周一）：恢复。\n", "")
                    : sevenDayAnswer();
            return AgentSynthesisResult.answerOnly(answer);
        };

        AgentObservation result = new SynthesizeActionHandler(
                new AgentSynthesisContextBuilder(), client).execute(
                plan.steps().getLast(), measuredContext(plan, state, collector));
        AgentRunMetricsSnapshot metrics = collector.snapshot();

        assertTrue(result.success());
        assertEquals(2, calls.get());
        assertEquals(2, metrics.finalValidationAttemptCount());
        assertEquals(1, metrics.finalValidationFailureCount());
        assertEquals(1, metrics.synthesisRepairTriggeredCount());
        assertEquals(1, metrics.synthesisRepairSucceededCount());
        assertTrue(metrics.synthesisRepairInstructionChars() > 0);
        assertTrue(metrics.synthesisRepairInstructionChars()
                <= SynthesizeActionHandler.MAX_REPAIR_INSTRUCTION_CHARS);
        assertEquals(synthesisContexts.getFirst().length(), metrics.synthesisContextChars());
        assertTrue(synthesisContexts.get(1).startsWith(synthesisContexts.getFirst()));
        assertEquals(
                metrics.synthesisRepairInstructionChars(),
                synthesisContexts.get(1).length() - synthesisContexts.getFirst().length());
    }

    @Test
    void invalidRepairUsesDeterministicRendererAndSameFinalGate() {
        AgentPlan plan = sevenDayPlan();
        AgentState state = validatedSevenDayState(plan);
        AgentRunMetricsCollector collector = new AgentRunMetricsCollector();
        AtomicInteger calls = new AtomicInteger();
        String invalid = sevenDayAnswer().replace("8月31日（周一）：恢复。\n", "");

        AgentObservation result = new SynthesizeActionHandler(
                new AgentSynthesisContextBuilder(), (goal, grounding) -> {
                    calls.incrementAndGet();
                    return AgentSynthesisResult.answerOnly(invalid);
                }).execute(plan.steps().getLast(), measuredContext(plan, state, collector));

        assertTrue(result.success());
        assertEquals(SynthesizeActionHandler.SYNTHESIS_COMPLETED,
                result.structuredData().get("code"));
        assertEquals("true", result.structuredData().get("deterministic"));
        assertFalse(result.summary().contains("audit"));
        assertEquals(2, calls.get());
        assertEquals(3, collector.snapshot().finalValidationAttemptCount());
        assertEquals(2, collector.snapshot().finalValidationFailureCount());
        assertEquals(0, collector.snapshot().synthesisRepairSucceededCount());
        assertEquals(1, collector.snapshot().deterministicSynthesisFallbackCount());
        assertEquals(AgentFallbackReason.INVALID_SYNTHESIS_AFTER_REPAIR,
                collector.snapshot().deterministicSynthesisFallbackReason());
    }

    @Test
    void totalTransientSynthesisOutageUsesTypedRendererWithoutAnotherLlmAttempt() {
        AgentPlan plan = trainingSevenDayPlan();
        AgentState state = validatedSevenDayState(plan);
        AgentRunMetricsCollector collector = new AgentRunMetricsCollector();
        AtomicInteger calls = new AtomicInteger();

        AgentObservation result = new SynthesizeActionHandler(
                new AgentSynthesisContextBuilder(), (goal, grounding) -> {
                    calls.incrementAndGet();
                    throw new AgentProviderException(
                            "SYNTHESIS", AgentProviderFailureCategory.TIMEOUT, null);
                }).execute(plan.steps().getLast(), measuredContext(plan, state, collector));

        assertTrue(result.success());
        assertEquals(1, calls.get());
        assertEquals(1, collector.snapshot().deterministicSynthesisFallbackCount());
        assertEquals(AgentFallbackReason.TIMEOUT,
                collector.snapshot().deterministicSynthesisFallbackReason());
        assertEquals(1, collector.snapshot().finalValidationAttemptCount());
        assertEquals(0, collector.snapshot().finalValidationFailureCount());
        assertTrue(result.summary().contains("8月27日（周四）"));
        assertTrue(result.summary().contains("9月2日（周三）"));
        assertTrue(result.summary().contains("正式训练"));
        assertFalse(result.summary().contains("audit"));
        assertFalse(result.summary().contains("provider"));
        assertFalse(result.summary().contains("fallback"));
    }

    @Test
    void synthesisAuthenticationFailureDoesNotUseRenderer() {
        AgentPlan plan = trainingSevenDayPlan();
        AgentState state = validatedSevenDayState(plan);
        AgentRunMetricsCollector collector = new AgentRunMetricsCollector();

        AgentObservation result = new SynthesizeActionHandler(
                new AgentSynthesisContextBuilder(), (goal, grounding) -> {
                    throw new AgentProviderException(
                            "SYNTHESIS", AgentProviderFailureCategory.NON_RETRYABLE, null);
                }).execute(plan.steps().getLast(), measuredContext(plan, state, collector));

        assertFalse(result.success());
        assertEquals(0, collector.snapshot().deterministicSynthesisFallbackCount());
    }

    @Test
    void validFirstSynthesisDoesNotTriggerRepair() {
        AgentPlan plan = sevenDayPlan();
        AgentState state = validatedSevenDayState(plan);
        AgentRunMetricsCollector collector = new AgentRunMetricsCollector();
        AtomicInteger calls = new AtomicInteger();

        AgentObservation result = new SynthesizeActionHandler(
                new AgentSynthesisContextBuilder(), (goal, grounding) -> {
                    calls.incrementAndGet();
                    return AgentSynthesisResult.answerOnly(sevenDayAnswer());
                }).execute(plan.steps().getLast(), measuredContext(plan, state, collector));

        assertTrue(result.success());
        assertEquals(1, calls.get());
        assertEquals(1, collector.snapshot().finalValidationAttemptCount());
        assertEquals(0, collector.snapshot().synthesisRepairTriggeredCount());
    }

    @Test
    void malformedEnvelopeRepairsOnceWithoutExposingRawProviderText() {
        AgentPlan plan = sevenDayPlan();
        AgentState state = validatedSevenDayState(plan);
        AtomicInteger calls = new AtomicInteger();

        AgentObservation result = new SynthesizeActionHandler(
                new AgentSynthesisContextBuilder(), (goal, grounding) ->
                calls.getAndIncrement() == 0
                        ? AgentSynthesisResult.invalid(AgentSynthesisParseError.MALFORMED_JSON)
                        : AgentSynthesisResult.answerOnly(sevenDayAnswer())).execute(
                plan.steps().getLast(), new AgentExecutionContext(plan.goal(), state, plan));

        assertTrue(result.success());
        assertEquals(2, calls.get());
        assertEquals(sevenDayAnswer().strip(), result.summary());
        assertFalse(result.summary().contains("audit"));
    }

    @Test
    void invalidTrainingAuditRepairsToValidTypedEnvelope() {
        AgentPlan plan = trainingSevenDayPlan();
        AgentState state = validatedSevenDayState(plan);
        AtomicInteger calls = new AtomicInteger();

        AgentObservation result = new SynthesizeActionHandler(
                new AgentSynthesisContextBuilder(), (goal, grounding) ->
                calls.getAndIncrement() == 0
                        ? trainingResult(List.of("2026-08-27", "2026-08-29", "2026-08-31"))
                        : trainingResult(List.of(
                                "2026-08-27", "2026-08-29", "2026-08-31", "2026-09-02")))
                .execute(plan.steps().getLast(),
                        new AgentExecutionContext(plan.goal(), state, plan));

        assertTrue(result.success());
        assertEquals(2, calls.get());
        assertEquals(sevenDayAnswer().strip(), result.summary());
    }

    private AgentState validatedState(AgentPlan plan) {
        AgentState state = new AgentState(plan);
        state.recordObservation(new AgentObservation("datetime", true, "日期"));
        state.recordObservation(new AgentObservation("weather", true, "真实天气"));
        state.recordObservation(new AgentObservation("rag", true, "未匹配", Map.of("matched", "false")));
        state.recordObservation(new AgentObservation(
                "validate", true, "校验通过", Map.of("code", ValidateActionHandler.VALIDATION_PASSED)));
        return state;
    }

    private AgentPlan plan() {
        return new AgentPlan("制定三日健康生活计划", List.of(
                step("datetime", AgentAction.GET_DATETIME, List.of()),
                step("weather", AgentAction.GET_WEATHER, List.of("datetime")),
                step("rag", AgentAction.RETRIEVE_KNOWLEDGE, List.of()),
                step("validate", AgentAction.VALIDATE, List.of("weather", "rag")),
                step("synthesis", AgentAction.SYNTHESIZE, List.of("validate"))
        ));
    }

    private AgentPlan sevenDayPlan() {
        return new AgentPlan("未来7天健康生活规划", List.of(
                step("datetime", AgentAction.GET_DATETIME, List.of()),
                step("weather", AgentAction.GET_WEATHER, List.of("datetime")),
                step("validate", AgentAction.VALIDATE, List.of("datetime", "weather")),
                step("synthesis", AgentAction.SYNTHESIZE, List.of("validate"))
        ));
    }

    private AgentPlan trainingSevenDayPlan() {
        AgentPlan base = sevenDayPlan();
        return new AgentPlan(
                "未来7天健康生活规划，每周训练4次，每次训练60分钟",
                base.steps());
    }

    private AgentSynthesisResult trainingResult(List<String> rawDates) {
        List<LocalDate> dates = rawDates.stream().map(LocalDate::parse).toList();
        Map<LocalDate, Integer> durations = dates.stream().collect(
                java.util.stream.Collectors.toMap(date -> date, ignored -> 60));
        return AgentSynthesisResult.parsed(new AgentSynthesisEnvelope(
                sevenDayAnswer(), AgentTrainingAudit.complete(dates, durations)));
    }

    private AgentState validatedSevenDayState(AgentPlan plan) {
        AgentState state = new AgentState(plan);
        state.recordObservation(new AgentObservation(
                "datetime", true, "当前日期 2026-08-27"));
        state.recordObservation(new AgentObservation(
                "weather", true,
                "2026-08-27 晴；2026-08-28 阴；2026-08-29 小雨",
                Map.of("period", "THREE_DAYS")));
        state.recordObservation(new AgentObservation(
                "validate", true, "校验通过",
                Map.of("code", ValidateActionHandler.VALIDATION_PASSED)));
        return state;
    }

    private AgentExecutionContext measuredContext(
            AgentPlan plan,
            AgentState state,
            AgentRunMetricsCollector collector
    ) {
        return new AgentExecutionContext(
                "user", plan.goal(), List.of(), false, state, plan,
                AgentRunMetrics.observe(collector));
    }

    private String sevenDayAnswer() {
        return """
                8月27日（周四）：安排。
                8月28日（周五）：安排。
                8月29日（周六）：安排。
                8月30日（周日）：恢复。
                8月31日（周一）：恢复。
                9月1日（周二）：安排。
                9月2日（周三）：安排。
                """;
    }

    private AgentStep step(String id, AgentAction action, List<String> dependencies) {
        return new AgentStep(id, action, "执行", "原因", dependencies);
    }
}
