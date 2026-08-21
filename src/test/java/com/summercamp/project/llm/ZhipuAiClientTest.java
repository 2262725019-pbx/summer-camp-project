package com.summercamp.project.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summercamp.project.config.AiChatProperties;
import com.summercamp.project.speech.PreparedAudio;
import com.summercamp.project.speech.VoiceInput;
import com.summercamp.project.speech.WechatAudioConverter;
import com.summercamp.project.tool.CalculatorTool;
import com.summercamp.project.tool.AddTodoTool;
import com.summercamp.project.tool.BotTool;
import com.summercamp.project.tool.ListTodosTool;
import com.summercamp.project.tool.QrCodeTool;
import com.summercamp.project.tool.TodoService;
import com.summercamp.project.tool.ToolContext;
import com.summercamp.project.tool.ToolDefinition;
import com.summercamp.project.tool.ToolRegistry;
import com.summercamp.project.tool.ToolResult;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.http.HttpHeaders;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ZhipuAiClientTest {

    private ObjectMapper objectMapper;
    private ZhipuAiClient client;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        client = newClient(HttpClient.newHttpClient());
    }

    private ZhipuAiClient newClient(HttpClient httpClient) {
        return newClient(
                httpClient,
                new ToolRegistry(List.of(new CalculatorTool(objectMapper)), objectMapper));
    }

    private ZhipuAiClient newClient(HttpClient httpClient, ToolRegistry toolRegistry) {
        AiChatProperties properties = new AiChatProperties(
                "https://open.bigmodel.cn/api/paas/v4",
                "/chat/completions",
                "/images/generations",
                "/audio/transcriptions",
                "test-key",
                "text-model",
                List.of("text-fallback-1", "text-fallback-2"),
                "vision-model",
                List.of("vision-fallback-1", "vision-fallback-2"),
                "image-model",
                "1024x1024",
                "asr-model",
                Duration.ofSeconds(10));
        return new ZhipuAiClient(
                properties,
                objectMapper,
                httpClient,
                new WechatAudioConverter(),
                toolRegistry);
    }

    @Test
    void shouldBuildTextChatCompletionsRequestWithHistory() {
        JsonNode payload = client.buildChatPayload(new ChatRequest(
                List.of(ChatMessage.user("上一问"), ChatMessage.assistant("上一答")),
                "你好",
                List.of()));

        assertEquals("text-model", payload.path("model").asText());
        assertEquals(4, payload.path("messages").size());
        assertEquals("system", payload.path("messages").get(0).path("role").asText());
        assertEquals("上一问", payload.path("messages").get(1).path("content").asText());
        assertEquals("你好", payload.path("messages").get(3).path("content").asText());
        assertEquals("auto", payload.path("tool_choice").asText());
        assertEquals("calculate", payload.path("tools").get(0)
                .path("function").path("name").asText());
        assertTrue(payload.path("tools").get(0).path("function")
                .path("parameters").path("required").isArray());
        assertEquals(
                List.of("text-model", "text-fallback-1", "text-fallback-2"),
                client.candidateChatModels(new ChatRequest(List.of(), "你好", List.of())));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void shouldExecuteRequestedToolAndSendResultBackToModel() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> toolRequest = mock(HttpResponse.class);
        HttpResponse<String> finalAnswer = mock(HttpResponse.class);
        when(toolRequest.statusCode()).thenReturn(200);
        when(toolRequest.body()).thenReturn("""
                {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{
                  "id":"call-1","type":"function","function":{
                    "name":"calculate","arguments":"{\\\"left\\\":125,\\\"operator\\\":\\\"MULTIPLY\\\",\\\"right\\\":36}"
                  }}]}}]}
                """);
        when(finalAnswer.statusCode()).thenReturn(200);
        when(finalAnswer.body()).thenReturn(
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"125乘36等于4500。\"}}]}");
        doReturn(toolRequest, finalAnswer).when(httpClient).send(
                any(HttpRequest.class),
                any(HttpResponse.BodyHandler.class));
        ZhipuAiClient toolClient = newClient(httpClient);

        ChatOutcome answer = toolClient.chat(
                new ChatRequest(List.of(), "帮我算125乘36", List.of()),
                new ToolContext("user-a", "帮我算125乘36"));

        assertEquals("125乘36等于4500。", answer.text());
        verify(httpClient, times(2)).send(
                any(HttpRequest.class),
                any(HttpResponse.BodyHandler.class));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void shouldExecuteDependentToolsAcrossMultipleRounds() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> addTodo = mock(HttpResponse.class);
        HttpResponse<String> listTodos = mock(HttpResponse.class);
        HttpResponse<String> finalAnswer = mock(HttpResponse.class);
        when(addTodo.statusCode()).thenReturn(200);
        when(addTodo.body()).thenReturn("""
                {"choices":[{"message":{"tool_calls":[{"id":"call-add","type":"function",
                  "function":{"name":"add_todo","arguments":"{\\"item\\":\\"写项目日报\\"}"}}]}}]}
                """);
        when(listTodos.statusCode()).thenReturn(200);
        when(listTodos.body()).thenReturn("""
                {"choices":[{"message":{"tool_calls":[{"id":"call-list","type":"function",
                  "function":{"name":"list_todos","arguments":"{}"}}]}}]}
                """);
        when(finalAnswer.statusCode()).thenReturn(200);
        when(finalAnswer.body()).thenReturn(
                "{\"choices\":[{\"message\":{\"content\":\"已添加，并确认待办列表中存在该任务。\"}}]}");
        doReturn(addTodo, listTodos, finalAnswer).when(httpClient).send(
                any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        TodoService todoService = new TodoService();
        ToolRegistry registry = new ToolRegistry(
                List.of(
                        new AddTodoTool(todoService, objectMapper),
                        new ListTodosTool(todoService, objectMapper)),
                objectMapper);
        ZhipuAiClient toolClient = newClient(httpClient, registry);

        ChatOutcome outcome = toolClient.chat(
                new ChatRequest(List.of(), "添加待办后再查看列表", List.of()),
                new ToolContext("user-a", "添加待办后再查看列表"));

        assertEquals("已添加，并确认待办列表中存在该任务。", outcome.text());
        assertEquals(List.of("写项目日报"), todoService.list("user-a"));
        verify(httpClient, times(3)).send(
                any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void shouldRunIndependentToolsInTheSameRoundConcurrently() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> toolRequest = mock(HttpResponse.class);
        HttpResponse<String> finalAnswer = mock(HttpResponse.class);
        when(toolRequest.statusCode()).thenReturn(200);
        when(toolRequest.body()).thenReturn("""
                {"choices":[{"message":{"tool_calls":[
                  {"id":"call-first","type":"function","function":{"name":"parallel_first","arguments":"{}"}},
                  {"id":"call-second","type":"function","function":{"name":"parallel_second","arguments":"{}"}}
                ]}}]}
                """);
        when(finalAnswer.statusCode()).thenReturn(200);
        when(finalAnswer.body()).thenReturn(
                "{\"choices\":[{\"message\":{\"content\":\"两个独立工具均已完成。\"}}]}");
        doReturn(toolRequest, finalAnswer).when(httpClient).send(
                any(HttpRequest.class), any(HttpResponse.BodyHandler.class));

        CountDownLatch bothStarted = new CountDownLatch(2);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maximumConcurrency = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry(
                List.of(
                        parallelTestTool("parallel_first", bothStarted, running, maximumConcurrency),
                        parallelTestTool("parallel_second", bothStarted, running, maximumConcurrency)),
                objectMapper);

        ChatOutcome outcome = newClient(httpClient, registry).chat(
                new ChatRequest(List.of(), "同时执行两个独立任务", List.of()),
                new ToolContext("user-a", "同时执行两个独立任务"));

        assertEquals("两个独立工具均已完成。", outcome.text());
        assertEquals(2, maximumConcurrency.get());
        verify(httpClient, times(2)).send(
                any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void shouldKeepOtherParallelToolsRunningWhenOneToolFails() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> toolRequest = mock(HttpResponse.class);
        HttpResponse<String> finalAnswer = mock(HttpResponse.class);
        when(toolRequest.statusCode()).thenReturn(200);
        when(toolRequest.body()).thenReturn("""
                {"choices":[{"message":{"tool_calls":[
                  {"id":"call-failed","type":"function","function":{"name":"failed_tool","arguments":"{}"}},
                  {"id":"call-success","type":"function","function":{"name":"successful_tool","arguments":"{}"}}
                ]}}]}
                """);
        when(finalAnswer.statusCode()).thenReturn(200);
        when(finalAnswer.body()).thenReturn(
                "{\"choices\":[{\"message\":{\"content\":\"成功工具的结果仍然可用。\"}}]}");
        doReturn(toolRequest, finalAnswer).when(httpClient).send(
                any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        AtomicInteger executionCount = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry(
                List.of(
                        parallelResultTool("failed_tool", executionCount, true),
                        parallelResultTool("successful_tool", executionCount, false)),
                objectMapper);

        ChatOutcome outcome = newClient(httpClient, registry).chat(
                new ChatRequest(List.of(), "同时执行两个任务，其中一个失败", List.of()),
                new ToolContext("user-a", "同时执行两个任务，其中一个失败"));

        assertEquals("成功工具的结果仍然可用。", outcome.text());
        assertEquals(2, executionCount.get());
        verify(httpClient, times(2)).send(
                any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void shouldPassFirstToolResultIntoANextRoundDependentToolCall() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> calculate = mock(HttpResponse.class);
        HttpResponse<String> resultPage = mock(HttpResponse.class);
        HttpResponse<String> qrCode = mock(HttpResponse.class);
        HttpResponse<String> finalAnswer = mock(HttpResponse.class);
        when(calculate.statusCode()).thenReturn(200);
        when(calculate.body()).thenReturn("""
                {"choices":[{"message":{"tool_calls":[{"id":"call-calculate","type":"function",
                  "function":{"name":"calculate","arguments":"{\\"expression\\":\\"125 * 36\\"}"}}]}}]}
                """);
        when(resultPage.statusCode()).thenReturn(200);
        when(resultPage.body()).thenReturn("""
                {"choices":[{"message":{"tool_calls":[{"id":"call-result-page","type":"function",
                  "function":{"name":"create_result_page","arguments":"{\\"expression\\":\\"125 * 36\\",\\"result\\":\\"4500\\"}"}}]}}]}
                """);
        when(qrCode.statusCode()).thenReturn(200);
        when(qrCode.body()).thenReturn("""
                {"choices":[{"message":{"tool_calls":[{"id":"call-qr","type":"function",
                  "function":{"name":"generate_qr_code","arguments":"{\\"text\\":\\"http://192.168.1.20:8080/results/test-result\\"}"}}]}}]}
                """);
        when(finalAnswer.statusCode()).thenReturn(200);
        when(finalAnswer.body()).thenReturn(
                "{\"choices\":[{\"message\":{\"content\":\"计算结果4500的二维码已生成。\"}}]}");
        doReturn(calculate, resultPage, qrCode, finalAnswer).when(httpClient).send(
                any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        ToolRegistry registry = new ToolRegistry(
                List.of(
                        new CalculatorTool(objectMapper),
                        resultPageTestTool(),
                        new QrCodeTool(objectMapper)),
                objectMapper);

        ChatOutcome outcome = newClient(httpClient, registry).chat(
                new ChatRequest(List.of(), "计算125乘36，然后把结果生成二维码", List.of()),
                new ToolContext("user-a", "计算125乘36，然后把结果生成二维码"));

        assertEquals("计算结果4500的二维码已生成。", outcome.text());
        assertEquals(1, outcome.media().size());
        assertEquals("qrcode.png", outcome.media().getFirst().fileName());
        assertTrue(outcome.media().getFirst().data().length > 0);
        verify(httpClient, times(4)).send(
                any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void shouldReturnImageProducedByATool() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> imageCall = mock(HttpResponse.class);
        HttpResponse<String> finalAnswer = mock(HttpResponse.class);
        when(imageCall.statusCode()).thenReturn(200);
        when(imageCall.body()).thenReturn("""
                {"choices":[{"message":{"tool_calls":[{"id":"call-image","type":"function",
                  "function":{"name":"test_image","arguments":"{}"}}]}}]}
                """);
        when(finalAnswer.statusCode()).thenReturn(200);
        when(finalAnswer.body()).thenReturn(
                "{\"choices\":[{\"message\":{\"content\":\"图片已经准备好了。\"}}]}");
        doReturn(imageCall, finalAnswer).when(httpClient).send(
                any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        BotTool imageTool = new BotTool() {
            private final ToolDefinition definition = new ToolDefinition(
                    "test_image",
                    "测试图片工具",
                    objectMapper.createObjectNode()
                            .put("type", "object")
                            .put("additionalProperties", false));

            @Override
            public ToolDefinition definition() {
                return definition;
            }

            @Override
            public ToolResult execute(com.fasterxml.jackson.databind.JsonNode arguments, ToolContext context) {
                return ToolResult.image(new byte[] {7, 8, 9}, "test.png", "测试图片");
            }
        };
        ZhipuAiClient toolClient = newClient(
                httpClient,
                new ToolRegistry(List.of(imageTool), objectMapper));

        ChatOutcome outcome = toolClient.chat(
                new ChatRequest(List.of(), "生成测试图片", List.of()),
                new ToolContext("user-a", "生成测试图片"));

        assertEquals("图片已经准备好了。", outcome.text());
        assertEquals(1, outcome.media().size());
        assertArrayEquals(new byte[] {7, 8, 9}, outcome.media().getFirst().data());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void shouldStopWhenModelKeepsRequestingTools() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> toolRequest = mock(HttpResponse.class);
        when(toolRequest.statusCode()).thenReturn(200);
        when(toolRequest.body()).thenReturn("""
                {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{
                  "id":"call-loop","type":"function","function":{
                    "name":"calculate","arguments":"{\\\"left\\\":1,\\\"operator\\\":\\\"ADD\\\",\\\"right\\\":1}"
                  }}]}}]}
                """);
        doReturn(toolRequest).when(httpClient).send(
                any(HttpRequest.class),
                any(HttpResponse.BodyHandler.class));
        ZhipuAiClient toolClient = newClient(httpClient);

        assertThrows(
                LlmException.class,
                () -> toolClient.chat(
                        new ChatRequest(List.of(), "一直调用工具", List.of()),
                        new ToolContext("user-a", "一直调用工具")));
        verify(httpClient, times(6)).send(
                any(HttpRequest.class),
                any(HttpResponse.BodyHandler.class));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void shouldSwitchToTextFallbackModelWhenPrimaryModelIsBusy() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> busy = mock(HttpResponse.class);
        HttpResponse<String> success = mock(HttpResponse.class);
        when(busy.statusCode()).thenReturn(429);
        when(busy.body()).thenReturn("{\"error\":{\"message\":\"busy\"}}");
        when(success.statusCode()).thenReturn(200);
        when(success.body()).thenReturn("{\"choices\":[{\"message\":{\"content\":\"备用模型回答\"}}]}");
        doReturn(busy, busy, success).when(httpClient).send(
                any(HttpRequest.class),
                any(HttpResponse.BodyHandler.class));
        ZhipuAiClient fallbackClient = newClient(httpClient);

        ChatOutcome answer = fallbackClient.chat(
                new ChatRequest(List.of(), "你好", List.of()),
                new ToolContext("user-a", "你好"));

        assertEquals("备用模型回答", answer.text());
        verify(httpClient, times(3)).send(
                any(HttpRequest.class),
                any(HttpResponse.BodyHandler.class));
    }

    @Test
    void shouldBuildVisionRequestWithBase64Image() {
        ChatRequest request = new ChatRequest(
                List.of(),
                "这张图是什么？",
                List.of(new ImageInput(new byte[] {1, 2, 3}, "image/png")));
        JsonNode payload = client.buildChatPayload(request);

        assertEquals("vision-model", payload.path("model").asText());
        assertEquals(
                List.of("vision-model", "vision-fallback-1", "vision-fallback-2"),
                client.candidateChatModels(request));
        JsonNode content = payload.path("messages").get(1).path("content");
        assertTrue(payload.path("tools").isMissingNode());
        assertEquals("image_url", content.get(0).path("type").asText());
        assertTrue(content.get(0).path("image_url").path("url").asText()
                .startsWith("data:image/png;base64,"));
        assertEquals("这张图是什么？", content.get(1).path("text").asText());
    }

    @Test
    void shouldBuildImageGenerationRequestAndParseResponses() throws Exception {
        JsonNode payload = client.buildImagePayload(
                List.of(ChatMessage.user("我们在讨论太空")),
                "画一只橘猫");
        assertEquals("image-model", payload.path("model").asText());
        assertEquals("1024x1024", payload.path("size").asText());
        assertTrue(payload.path("prompt").asText().contains("我们在讨论太空"));
        assertTrue(payload.path("prompt").asText().endsWith("画一只橘猫"));

        JsonNode textResponse = objectMapper.readTree("""
                {"choices":[{"message":{"role":"assistant","content":"识别结果"}}]}
                """);
        JsonNode imageResponse = objectMapper.readTree("""
                {"created":123,"data":[{"url":"https://example.com/generated.png"}]}
                """);

        assertEquals("识别结果", client.extractOutputText(textResponse));
        assertEquals("https://example.com/generated.png", client.extractGeneratedImageUrl(imageResponse));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void shouldTrustSupportedImageSignatureWhenServerContentTypeIsWrong() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> generation = mock(HttpResponse.class);
        HttpResponse<InputStream> download = mock(HttpResponse.class);
        byte[] jpeg = new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1, 2, 3};
        when(generation.statusCode()).thenReturn(200);
        when(generation.body()).thenReturn(
                "{\"data\":[{\"url\":\"https://example.com/generated-image\"}]}");
        when(download.statusCode()).thenReturn(200);
        when(download.body()).thenReturn(new ByteArrayInputStream(jpeg));
        when(download.headers()).thenReturn(HttpHeaders.of(
                Map.of("Content-Type", List.of("image/png")),
                (name, value) -> true));
        doReturn(generation, download).when(httpClient).send(
                any(HttpRequest.class), any(HttpResponse.BodyHandler.class));

        GeneratedImage image = newClient(httpClient).generate(List.of(), "一只小猫");

        assertArrayEquals(jpeg, image.data());
        assertEquals("image/jpeg", image.mediaType());
        assertEquals("generated-image.jpg", image.fileName());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void shouldRejectDownloadedContentWithoutAnImageSignature() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> generation = mock(HttpResponse.class);
        HttpResponse<InputStream> download = mock(HttpResponse.class);
        when(generation.statusCode()).thenReturn(200);
        when(generation.body()).thenReturn(
                "{\"data\":[{\"url\":\"https://example.com/generated-image\"}]}");
        when(download.statusCode()).thenReturn(200);
        when(download.body()).thenReturn(new ByteArrayInputStream("<html>error</html>".getBytes()));
        when(download.headers()).thenReturn(HttpHeaders.of(
                Map.of("Content-Type", List.of("image/png")),
                (name, value) -> true));
        doReturn(generation, download).when(httpClient).send(
                any(HttpRequest.class), any(HttpResponse.BodyHandler.class));

        LlmException exception = assertThrows(
                LlmException.class,
                () -> newClient(httpClient).generate(List.of(), "一只小猫"));

        assertTrue(exception.getMessage().contains("不是受支持的图片格式"));
    }

    @Test
    void shouldBuildIntentAndMultipartAsrRequests() {
        JsonNode intent = client.buildIntentPayload("帮我查明天北京天气");
        byte[] multipart = client.buildMultipartBody(
                "test-boundary",
                new PreparedAudio(new byte[] {1, 2, 3}, "voice.wav", "audio/wav"));
        String multipartText = new String(multipart, java.nio.charset.StandardCharsets.ISO_8859_1);

        assertEquals("json_object", intent.path("response_format").path("type").asText());
        assertTrue(multipartText.contains("name=\"model\""));
        assertTrue(multipartText.contains("asr-model"));
        assertTrue(multipartText.contains("filename=\"voice.wav\""));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void shouldSendMultipartAsrRequestAndReadTranscript() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"text\":\"今天天气怎么样\"}");
        doReturn(response).when(httpClient).send(
                any(HttpRequest.class),
                any(HttpResponse.BodyHandler.class));
        ZhipuAiClient speechClient = newClient(httpClient);

        String transcript = speechClient.transcribe(new VoiceInput(
                new byte[] {1, 2, 3, 4}, "", 1, 16, 24_000, 100));

        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(
                requestCaptor.capture(),
                any(HttpResponse.BodyHandler.class));
        HttpRequest request = requestCaptor.getValue();
        assertEquals("今天天气怎么样", transcript);
        assertTrue(request.uri().getPath().endsWith("/audio/transcriptions"));
        assertTrue(request.headers().firstValue("Content-Type").orElseThrow()
                .startsWith("multipart/form-data; boundary="));
    }

    private BotTool parallelTestTool(
            String name,
            CountDownLatch bothStarted,
            AtomicInteger running,
            AtomicInteger maximumConcurrency) {
        var schema = objectMapper.createObjectNode()
                .put("type", "object")
                .put("additionalProperties", false);
        ToolDefinition definition = new ToolDefinition(name, "并行测试工具", schema);
        return new BotTool() {
            @Override
            public ToolDefinition definition() {
                return definition;
            }

            @Override
            public ToolResult execute(JsonNode arguments, ToolContext context) {
                int current = running.incrementAndGet();
                maximumConcurrency.accumulateAndGet(current, Math::max);
                bothStarted.countDown();
                try {
                    if (!bothStarted.await(1, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("两个工具没有并行启动");
                    }
                    return ToolResult.text(name + "完成");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("并行测试被中断", exception);
                } finally {
                    running.decrementAndGet();
                }
            }

            @Override
            public boolean parallelSafe() {
                return true;
            }
        };
    }

    private BotTool parallelResultTool(String name, AtomicInteger executionCount, boolean shouldFail) {
        var schema = objectMapper.createObjectNode()
                .put("type", "object")
                .put("additionalProperties", false);
        ToolDefinition definition = new ToolDefinition(name, "并行失败隔离测试工具", schema);
        return new BotTool() {
            @Override
            public ToolDefinition definition() {
                return definition;
            }

            @Override
            public ToolResult execute(JsonNode arguments, ToolContext context) {
                executionCount.incrementAndGet();
                if (shouldFail) {
                    throw new IllegalStateException("测试工具故意失败");
                }
                return ToolResult.text(name + "完成");
            }

            @Override
            public boolean parallelSafe() {
                return true;
            }
        };
    }

    private BotTool resultPageTestTool() {
        var schema = objectMapper.createObjectNode().put("type", "object");
        var properties = schema.putObject("properties");
        properties.putObject("expression").put("type", "string");
        properties.putObject("result").put("type", "string");
        schema.putArray("required").add("expression").add("result");
        schema.put("additionalProperties", false);
        ToolDefinition definition = new ToolDefinition(
                "create_result_page", "创建测试结果页", schema);
        return new BotTool() {
            @Override
            public ToolDefinition definition() {
                return definition;
            }

            @Override
            public ToolResult execute(JsonNode arguments, ToolContext context) {
                var result = objectMapper.createObjectNode();
                result.put("url", "http://192.168.1.20:8080/results/test-result");
                return ToolResult.data(result);
            }
        };
    }

}
