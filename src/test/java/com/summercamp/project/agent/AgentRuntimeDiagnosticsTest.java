package com.summercamp.project.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class AgentRuntimeDiagnosticsTest {
    @Test
    void helperUsesOnlySafeActionCodeAndRecoverableFields() {
        AgentStep step = new AgentStep(
                "S1",
                AgentAction.GET_WEATHER,
                "sensitive description",
                "sensitive reason",
                List.of(),
                Map.of("location", "sensitive input")
        );
        AgentObservation observation = new AgentObservation(
                step.id(),
                false,
                "sensitive observation summary",
                Map.of(
                        "code", "WEATHER_FAILED",
                        "recoverable", "true",
                        "debug", "sensitive structured detail"
                )
        );

        String diagnostic = AgentRuntimeDiagnostics.stepFailed(step.action(), observation);

        assertEquals(
                "Agent step 失败：action=GET_WEATHER, code=WEATHER_FAILED, recoverable=true",
                diagnostic
        );
        assertFalse(diagnostic.contains("sensitive input"));
        assertFalse(diagnostic.contains("sensitive observation summary"));
        assertFalse(diagnostic.contains("sensitive structured detail"));
    }

    @Test
    void helperMapsMissingOrUnsafeCodesToStableFallbacks() {
        AgentObservation noCode = new AgentObservation("S1", false, "not logged");
        AgentObservation unsafeCode = new AgentObservation(
                "S2", false, "not logged", Map.of("code", "user data\nsecret"));

        assertTrue(AgentRuntimeDiagnostics.stepFailed(AgentAction.VALIDATE, noCode)
                .contains("code=VALIDATION_FAILED"));
        assertTrue(AgentRuntimeDiagnostics.stepFailed(AgentAction.SYNTHESIZE, noCode)
                .contains("code=SYNTHESIS_FAILED"));
        assertTrue(AgentRuntimeDiagnostics.stepFailed(AgentAction.GET_WEATHER, noCode)
                .contains("code=HANDLER_FAILURE"));
        assertTrue(AgentRuntimeDiagnostics.stepFailed(AgentAction.GET_WEATHER, unsafeCode)
                .contains("code=UNKNOWN_RUNTIME_FAILURE"));
        assertEquals(
                "Agent step 跳过：action=SYNTHESIZE, reason=UPSTREAM_FAILED",
                AgentRuntimeDiagnostics.stepSkipped(AgentAction.SYNTHESIZE)
        );
    }

    @Test
    void executorLogsLifecycleWithoutChangingTerminalStatesOrLeakingPayloads() {
        AgentPlan plan = new AgentPlan("sensitive original goal", List.of(
                new AgentStep(
                        "S1", AgentAction.GET_DATETIME, "secret description", "secret reason",
                        List.of(), Map.of("private", "secret input")),
                new AgentStep(
                        "S2", AgentAction.GET_WEATHER, "secret description", "secret reason",
                        List.of("S1"), Map.of("location", "secret location")),
                new AgentStep(
                        "S3", AgentAction.SYNTHESIZE, "secret description", "secret reason",
                        List.of("S2"), Map.of())
        ));
        AgentActionHandler datetime = handler(
                AgentAction.GET_DATETIME,
                step -> new AgentObservation(step.id(), true, "secret success summary"));
        AgentActionHandler weather = handler(
                AgentAction.GET_WEATHER,
                step -> new AgentObservation(
                        step.id(), false, "secret failure summary",
                        Map.of("code", "WEATHER_FAILED", "recoverable", "false")));
        AgentExecutor executor = new AgentExecutor(
                new AgentActionHandlerRegistry(List.of(datetime, weather)));

        Logger logger = (Logger) LoggerFactory.getLogger(AgentExecutor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        AgentState state;
        try {
            state = executor.execute(plan);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertEquals(AgentStepStatus.COMPLETED, state.statusOf("S1"));
        assertEquals(AgentStepStatus.FAILED, state.statusOf("S2"));
        assertEquals(AgentStepStatus.SKIPPED, state.statusOf("S3"));
        List<String> messages = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
        assertEquals(List.of(
                "Agent step 开始：action=GET_DATETIME",
                "Agent step 完成：action=GET_DATETIME, status=COMPLETED",
                "Agent step 开始：action=GET_WEATHER",
                "Agent step 失败：action=GET_WEATHER, code=WEATHER_FAILED, recoverable=false",
                "Agent step 跳过：action=SYNTHESIZE, reason=UPSTREAM_FAILED"
        ), messages);
        String joined = String.join("\n", messages);
        assertFalse(joined.contains("sensitive original goal"));
        assertFalse(joined.contains("secret input"));
        assertFalse(joined.contains("secret location"));
        assertFalse(joined.contains("secret success summary"));
        assertFalse(joined.contains("secret failure summary"));
    }

    private AgentActionHandler handler(
            AgentAction action,
            java.util.function.Function<AgentStep, AgentObservation> result
    ) {
        return new AgentActionHandler() {
            @Override
            public AgentAction action() {
                return action;
            }

            @Override
            public AgentObservation execute(AgentStep step, AgentExecutionContext context) {
                return result.apply(step);
            }
        };
    }
}
