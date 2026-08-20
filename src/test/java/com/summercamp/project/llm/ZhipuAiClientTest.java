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

}
