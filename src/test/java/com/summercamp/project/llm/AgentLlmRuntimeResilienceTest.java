package com.summercamp.project.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summercamp.project.agent.AgentPlan;
import com.summercamp.project.agent.AgentRunMetrics;
import com.summercamp.project.agent.AgentRunMetricsCollector;
import com.summercamp.project.agent.LlmAgentPlanner;
import com.summercamp.project.config.AiChatProperties;
import com.summercamp.project.speech.WechatAudioConverter;
import com.summercamp.project.tool.ToolRegistry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class AgentLlmRuntimeResilienceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void primaryPlanningSuccessUsesOneAttemptAndAgentTimeout() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        stubHttp(httpClient, successResponse("{\"goal\":\"健康计划\",\"steps\":[]}"));
        ZhipuAiClient client = client(httpClient, List.of("fallback-model"), Duration.ofSeconds(40));

        String result = client.generatePlan("健康计划", "只返回 JSON");

        assertEquals("{\"goal\":\"健康计划\",\"steps\":[]}", result);
        ArgumentCaptor<HttpRequest> requests = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(requests.capture(), any(HttpResponse.BodyHandler.class));
        assertEquals(Duration.ofSeconds(40), requests.getValue().timeout().orElseThrow());
        assertEquals("primary-model", requestJson(requests.getValue()).path("model").asText());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void planningTimeoutFallsBackExactlyOnceWithFallbackModel() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        stubHttp(
                httpClient,
                new HttpTimeoutException("timeout"),
                successResponse("{\"goal\":\"健康计划\",\"steps\":[]}"));
        ZhipuAiClient client = client(httpClient, List.of("fallback-model"), Duration.ofSeconds(40));

        AgentRunMetricsCollector collector = new AgentRunMetricsCollector();
        String result = client.generatePlan(
                "健康计划",
                "只返回 JSON",
                AgentRunMetrics.observe(collector)
                        .withLlmPhase(AgentRunMetrics.LlmPhase.PLANNING));

        assertTrue(result.contains("健康计划"));
        assertEquals(2, collector.snapshot().llmRequestCount());
        assertEquals(2, collector.snapshot().planningLlmRequestCount());
        ArgumentCaptor<HttpRequest> requests = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient, times(2)).send(requests.capture(), any(HttpResponse.BodyHandler.class));
        assertEquals(List.of("primary-model", "fallback-model"), requestModels(requests.getAllValues()));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void planningRateLimitFallsBackOnce() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        stubHttp(
                httpClient,
                errorResponse(429),
                successResponse("{\"goal\":\"计划\",\"steps\":[]}"));

        client(httpClient, List.of("fallback-model"), Duration.ofSeconds(40))
                .generatePlan("计划", "只返回 JSON");

        verify(httpClient, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 503})
    @SuppressWarnings({"rawtypes", "unchecked"})
    void planningServerFailureFallsBackOnce(int statusCode) throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        stubHttp(
                httpClient,
                errorResponse(statusCode),
                successResponse("{\"goal\":\"计划\",\"steps\":[]}"));

        client(httpClient, List.of("fallback-model"), Duration.ofSeconds(40))
                .generatePlan("计划", "只返回 JSON");

        verify(httpClient, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void planningConnectivityFailureFallsBackOnce() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        stubHttp(
                httpClient,
                new IOException("connection reset"),
                successResponse("{\"goal\":\"计划\",\"steps\":[]}"));

        client(httpClient, List.of("fallback-model"), Duration.ofSeconds(40))
                .generatePlan("计划", "只返回 JSON");

        verify(httpClient, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403})
    @SuppressWarnings({"rawtypes", "unchecked"})
    void nonRetryableHttpFailureDoesNotFallback(int statusCode) throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        stubHttp(httpClient, errorResponse(statusCode));

        LlmException failure = assertThrows(
                LlmException.class,
                () -> client(httpClient, List.of("fallback-model"), Duration.ofSeconds(40))
                        .generatePlan("计划", "只返回 JSON"));

        assertTrue(failure.getMessage().contains("PLANNING_NON_RETRYABLE"));
        verify(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void interruptionPreservesFlagAndDoesNotFallback() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        stubHttp(httpClient, new InterruptedException("interrupted"));

        LlmException failure = assertThrows(
                LlmException.class,
                () -> client(httpClient, List.of("fallback-model"), Duration.ofSeconds(40))
                        .generatePlan("计划", "只返回 JSON"));

        assertTrue(Thread.currentThread().isInterrupted());
        assertTrue(failure.getMessage().contains("PLANNING_INTERRUPTED"));
        verify(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void bothPlanningModelsTimingOutStopsAtTwoTotalAttempts() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        stubHttp(
                httpClient,
                new HttpTimeoutException("primary timeout"),
                new HttpTimeoutException("fallback timeout"));

        LlmException failure = assertThrows(
                LlmException.class,
                () -> client(httpClient, List.of("fallback-1", "fallback-2"), Duration.ofSeconds(40))
                        .generatePlan("计划", "只返回 JSON"));

        assertTrue(failure.getMessage().contains("PLANNING_TIMEOUT"));
        verify(httpClient, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void transientFailureWithoutFallbackUsesOneAttempt() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        stubHttp(httpClient, new HttpTimeoutException("timeout"));

        LlmException failure = assertThrows(
                LlmException.class,
                () -> client(httpClient, List.of(" "), Duration.ofSeconds(40))
                        .generatePlan("计划", "只返回 JSON"));

        assertTrue(failure.getMessage().contains("PLANNING_TIMEOUT"));
        verify(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void candidateModelsAreDistinctNonBlankAndCappedAtOneFallback() {
        ZhipuAiClient client = client(
                mock(HttpClient.class),
                List.of(" ", "primary-model", "fallback-1", "fallback-2"),
                Duration.ofSeconds(40));

        assertEquals(List.of("primary-model", "fallback-1"), client.candidateAgentModels());
    }

    @Test
    void planningPayloadForEitherModelIsJsonOnlyAndToolIsolated() {
        ZhipuAiClient client = client(
                mock(HttpClient.class), List.of("fallback-model"), Duration.ofSeconds(40));

        for (String model : List.of("primary-model", "fallback-model")) {
            JsonNode payload = client.buildPlanningPayload("健康计划", "只返回 JSON", model);
            assertEquals(model, payload.path("model").asText());
            assertEquals("json_object", payload.path("response_format").path("type").asText());
            assertEquals("disabled", payload.path("thinking").path("type").asText());
            assertTrue(payload.path("tools").isMissingNode());
            assertTrue(payload.path("tool_choice").isMissingNode());
        }
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void providerFallbackDoesNotConsumePlannerRepairAttempt() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        stubHttp(httpClient, new HttpTimeoutException("timeout"), successResponse(validPlanJson()));
        ZhipuAiClient planningClient = client(
                httpClient, List.of("fallback-model"), Duration.ofSeconds(40));

        AgentPlan plan = new LlmAgentPlanner(planningClient, objectMapper)
                .plan("制定综合健康生活方案");

        assertEquals(5, plan.steps().size());
        assertEquals(1, LlmAgentPlanner.MAX_REPAIR_ATTEMPTS);
        verify(httpClient, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void synthesisTransientFailureFallsBackWithToolIsolatedPayload() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        stubHttp(httpClient, errorResponse(503), successResponse("最终健康计划"));
        ZhipuAiClient client = client(httpClient, List.of("fallback-model"), Duration.ofSeconds(40));

        AgentRunMetricsCollector collector = new AgentRunMetricsCollector();
        String result = client.synthesize(
                "制定健康计划",
                "Observation 安全上下文",
                AgentRunMetrics.observe(collector)
                        .withLlmPhase(AgentRunMetrics.LlmPhase.SYNTHESIS));

        assertEquals("最终健康计划", result);
        assertEquals(2, collector.snapshot().llmRequestCount());
        assertEquals(2, collector.snapshot().synthesisLlmRequestCount());
        assertEquals(
                client.synthesisInstructionChars(),
                collector.snapshot().synthesisInstructionChars());
        ArgumentCaptor<HttpRequest> requests = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient, times(2)).send(requests.capture(), any(HttpResponse.BodyHandler.class));
        List<JsonNode> payloads = requests.getAllValues().stream().map(this::requestJson).toList();
        assertEquals(List.of("primary-model", "fallback-model"),
                payloads.stream().map(node -> node.path("model").asText()).toList());
        assertTrue(payloads.stream().allMatch(node -> node.path("tools").isMissingNode()));
        assertTrue(payloads.stream().allMatch(node -> node.path("tool_choice").isMissingNode()));
        assertTrue(payloads.stream().allMatch(node -> node.path("max_tokens").asInt() == 2_000));
    }

    @Test
    void invalidAgentTimeoutIsRejected() {
        AiChatProperties zero = properties(List.of(), Duration.ZERO);
        AiChatProperties negative = properties(List.of(), Duration.ofSeconds(-1));

        assertThrows(IllegalStateException.class, zero::validate);
        assertThrows(IllegalStateException.class, negative::validate);
    }

    @Test
    void providerFailureClassifierUsesStableTypesAndStatuses() {
        assertFailure(
                new LlmException("safe", new HttpTimeoutException("secret")),
                AgentProviderFailureClassifier.Category.TIMEOUT,
                true);
        assertFailure(
                new LlmException("safe", new IOException("secret")),
                AgentProviderFailureClassifier.Category.CONNECTIVITY,
                true);
        assertFailure(
                new ZhipuAiClient.ZhipuHttpException(429, "secret"),
                AgentProviderFailureClassifier.Category.RATE_LIMIT,
                true);
        assertFailure(
                new ZhipuAiClient.ZhipuHttpException(503, "secret"),
                AgentProviderFailureClassifier.Category.SERVER_ERROR,
                true);
        assertFailure(
                new ZhipuAiClient.InvalidProviderResponseException("secret"),
                AgentProviderFailureClassifier.Category.INVALID_PROVIDER_RESPONSE,
                false);
        assertFailure(
                new ZhipuAiClient.AgentRequestSerializationException(
                        "secret", new IOException("secret")),
                AgentProviderFailureClassifier.Category.NON_RETRYABLE,
                false);
        assertFailure(
                new LlmException("safe", new InterruptedException("secret")),
                AgentProviderFailureClassifier.Category.INTERRUPTED,
                false);
        assertFailure(
                new IllegalStateException("secret"),
                AgentProviderFailureClassifier.Category.NON_RETRYABLE,
                false);
        assertFailure(
                new LlmException("secret"),
                AgentProviderFailureClassifier.Category.UNKNOWN_PROVIDER_FAILURE,
                false);
    }

    private void assertFailure(
            Throwable throwable,
            AgentProviderFailureClassifier.Category category,
            boolean fallbackEligible) {
        AgentProviderFailureClassifier.Failure failure =
                AgentProviderFailureClassifier.classify(throwable);
        assertEquals(category, failure.category());
        assertEquals(fallbackEligible, failure.fallbackEligible());
    }

    private ZhipuAiClient client(
            HttpClient httpClient,
            List<String> fallbackModels,
            Duration agentTimeout) {
        return new ZhipuAiClient(
                properties(fallbackModels, agentTimeout),
                objectMapper,
                httpClient,
                new WechatAudioConverter(),
                mock(ToolRegistry.class));
    }

    private AiChatProperties properties(List<String> fallbackModels, Duration agentTimeout) {
        return new AiChatProperties(
                "https://open.bigmodel.cn/api/paas/v4",
                "/chat/completions",
                "/images/generations",
                "/audio/transcriptions",
                "test-key",
                "primary-model",
                fallbackModels,
                "vision-model",
                List.of(),
                "image-model",
                "1024x1024",
                "asr-model",
                Duration.ofSeconds(60),
                agentTimeout,
                2_000);
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> successResponse(String content) throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        whenStatus(response, 200);
        var root = objectMapper.createObjectNode();
        root.putArray("choices")
                .addObject()
                .putObject("message")
                .put("content", content);
        org.mockito.Mockito.when(response.body())
                .thenReturn(objectMapper.writeValueAsString(root));
        return response;
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> errorResponse(int statusCode) {
        HttpResponse<String> response = mock(HttpResponse.class);
        whenStatus(response, statusCode);
        org.mockito.Mockito.when(response.body()).thenReturn("{\"error\":{\"message\":\"secret\"}}");
        return response;
    }

    private void whenStatus(HttpResponse<String> response, int statusCode) {
        org.mockito.Mockito.when(response.statusCode()).thenReturn(statusCode);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void stubHttp(HttpClient httpClient, Object... outcomes) throws Exception {
        AtomicInteger invocation = new AtomicInteger();
        doAnswer(ignored -> {
            Object outcome = outcomes[Math.min(invocation.getAndIncrement(), outcomes.length - 1)];
            if (outcome instanceof Throwable throwable) {
                throw throwable;
            }
            return outcome;
        }).when(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    private List<String> requestModels(List<HttpRequest> requests) {
        return requests.stream().map(request -> requestJson(request).path("model").asText()).toList();
    }

    private JsonNode requestJson(HttpRequest request) {
        try {
            return objectMapper.readTree(requestBody(request));
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private String requestBody(HttpRequest request) {
        HttpRequest.BodyPublisher publisher = request.bodyPublisher().orElseThrow();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        CompletableFuture<Void> completed = new CompletableFuture<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                body.writeBytes(bytes);
            }

            @Override
            public void onError(Throwable throwable) {
                completed.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                completed.complete(null);
            }
        });
        completed.join();
        return body.toString(StandardCharsets.UTF_8);
    }

    private String validPlanJson() {
        return """
                {
                  "goal":"制定综合健康生活方案",
                  "steps":[
                    {"id":"S1","action":"GET_DATETIME","description":"获取日期","reason":"建立时间基准",\
                     "dependsOn":[],"inputs":{}},
                    {"id":"S2","action":"RETRIEVE_KNOWLEDGE","description":"检索知识","reason":"获取一般建议",\
                     "dependsOn":["S1"],"inputs":{"query":"大学生健康生活"}},
                    {"id":"S3","action":"CREATE_TODO","description":"创建待办","reason":"帮助执行计划",\
                     "dependsOn":["S2"],"inputs":{"item":"执行健康生活计划"}},
                    {"id":"S4","action":"VALIDATE","description":"验证结果","reason":"确保结果完整",\
                     "dependsOn":["S3"],"inputs":{}},
                    {"id":"S5","action":"SYNTHESIZE","description":"汇总计划","reason":"输出最终成品",\
                     "dependsOn":["S4"],"inputs":{}}
                  ]
                }
                """;
    }
}
