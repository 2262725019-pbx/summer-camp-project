package com.summercamp.project.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.summercamp.project.config.AiChatProperties;
import com.summercamp.project.intent.IntentClassificationClient;
import com.summercamp.project.intent.IntentResult;
import com.summercamp.project.intent.IntentType;
import com.summercamp.project.speech.PreparedAudio;
import com.summercamp.project.speech.SpeechRecognitionException;
import com.summercamp.project.speech.SpeechToTextClient;
import com.summercamp.project.speech.VoiceInput;
import com.summercamp.project.speech.WechatAudioConverter;
import com.summercamp.project.tool.ToolDefinition;
import com.summercamp.project.tool.ToolContext;
import com.summercamp.project.tool.ToolRegistry;
import com.summercamp.project.tool.ToolResult;
import com.summercamp.project.weather.WeatherPeriod;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 智谱开放平台客户端，负责对话、多模态、意图分类和语音能力。 */
@Component
public class ZhipuAiClient implements
        ChatModelClient,
        ImageGenerationClient,
        IntentClassificationClient,
        SpeechToTextClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(ZhipuAiClient.class);
    private static final String SYSTEM_INSTRUCTIONS = """
            你是一个运行在微信中的中文 AI 助手。请准确、友好、简洁地回答。
            当用户发送图片时，请结合图片内容和文字问题回答；不确定时明确说明。
            只有当前请求确实包含图片数据时，才可以声称看到了图片。
            历史消息中的“用户发送了图片”只是占位说明，不包含图片内容；需要时请让用户重新发送图片。
            应用会在用户发送语音时把你的回答合成为微信语音，因此不要声称自己只能发送文字或不能语音回复。
            涉及真实天气、温度、降雨或带伞问题时必须调用 get_weather，不得凭记忆编造天气。
            用户要求进行明确数值计算时调用 calculate，不要自行心算替代工具。
            用户问当前日期、时间或星期时调用 get_current_datetime。
            待办事项依次使用 add_todo、list_todos、complete_todo，并保持当前微信用户的数据隔离。
            用户要求清除上下文时调用 clear_memory；要求二维码时调用 generate_qr_code。
            generate_image 和 generate_qr_code 会产生真实图片，不要声称图片无法发送。
            复杂任务可以连续调用多个工具，后一步可以依据前一步的工具结果继续执行。
            工具返回 success=false 时，简洁说明失败原因；天气工具成功时必须保持数值和发布时间准确。
            不要声称执行了实际上没有执行的操作。
            """;
    private static final int MAX_ATTEMPTS = 2;
    private static final int MAX_TOOL_ROUNDS = 5;
    private static final int MAX_TOOL_CALLS_PER_ROUND = 4;
    private static final int MAX_PROVIDER_ERROR_LENGTH = 240;
    private static final int MAX_IMAGE_CONTEXT_MESSAGES = 4;
    private static final int MAX_IMAGE_CONTEXT_CHARS = 2_000;
    private static final int MAX_GENERATED_IMAGE_BYTES = 10 * 1024 * 1024;
    private static final String INTENT_INSTRUCTIONS = """
            你是微信机器人的意图分类器，只返回 JSON，不要解释。
            intent 只能是 CHAT、WEATHER、IMAGE_GENERATION、IMAGE_ANALYSIS_REQUEST。
            WEATHER 需要提取中国城市或区县 location，并识别 period：
            CURRENT、TODAY、TOMORROW、DAY_AFTER_TOMORROW、THREE_DAYS。
            IMAGE_GENERATION 将绘图描述放入 prompt；其他字段使用空字符串。
            输出格式：
            {"intent":"CHAT","location":"","period":"CURRENT","prompt":""}
            """;

    private final AiChatProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final WechatAudioConverter audioConverter;
    private final ToolRegistry toolRegistry;

    public ZhipuAiClient(
            AiChatProperties properties,
            ObjectMapper objectMapper,
            HttpClient httpClient,
            WechatAudioConverter audioConverter,
            ToolRegistry toolRegistry) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.audioConverter = audioConverter;
        this.toolRegistry = toolRegistry;
    }

    @Override
    public ChatOutcome chat(ChatRequest request, ToolContext context) {
        LlmException lastFailure = null;
        List<String> models = candidateChatModels(request);
        for (int index = 0; index < models.size(); index++) {
            String model = models.get(index);
            try {
                return chatWithModel(request, model, context);
            } catch (ZhipuHttpException exception) {
                lastFailure = exception;
                if (canTryFallback(exception.statusCode())
                        && index + 1 < models.size()) {
                    LOGGER.warn("智谱模型 {} 暂时不可用，将尝试备用模型 {}：{}",
                            model,
                            models.get(index + 1),
                            exception.getMessage());
                    continue;
                }
                throw exception;
            }
        }
        throw lastFailure == null ? new LlmException("没有可用的智谱模型") : lastFailure;
    }

    private ChatOutcome chatWithModel(ChatRequest request, String model, ToolContext context) {
        ObjectNode payload = buildChatPayload(request, model);
        List<ChatOutcome.Media> media = new ArrayList<>();
        for (int round = 0; round <= MAX_TOOL_ROUNDS; round++) {
            JsonNode response = executeJson(properties.chatEndpoint(), payload);
            List<ModelToolCall> toolCalls = extractToolCalls(response);
            if (toolCalls.isEmpty()) {
                String answer = extractOutputText(response);
                if (answer == null || answer.isBlank()) {
                    throw new LlmException("智谱响应中没有可用文本");
                }
                return new ChatOutcome(answer.strip(), media);
            }
            if (!request.images().isEmpty()) {
                throw new LlmException("视觉模型返回了不支持的工具调用");
            }
            if (round >= MAX_TOOL_ROUNDS) {
                throw new LlmException("模型连续请求工具，超过最大调用轮数");
            }
            appendAssistantToolRequest(payload, response);
            for (ModelToolCall toolCall : toolCalls) {
                LOGGER.info("模型请求调用工具：{}", toolCall.name());
                ToolRegistry.Invocation invocation = toolRegistry.invoke(
                        toolCall.name(), toolCall.arguments(), context);
                ToolResult result = invocation.result();
                if (invocation.success()) {
                    if (result instanceof ToolResult.Completed completed) {
                        return new ChatOutcome(completed.reply(), media);
                    }
                    if (result instanceof ToolResult.Image image) {
                        media.add(new ChatOutcome.Media(
                                image.data(), image.fileName(), image.caption()));
                    }
                }
                payload.withArray("messages").addObject()
                        .put("role", "tool")
                        .put("tool_call_id", toolCall.id())
                        .put("content", invocation.modelContent());
            }
        }
        throw new LlmException("模型工具调用没有产生最终回答");
    }

    @Override
    public GeneratedImage generate(List<ChatMessage> history, String prompt) {
        JsonNode response = executeJson(properties.imageEndpoint(), buildImagePayload(history, prompt));
        String imageUrl = extractGeneratedImageUrl(response);
        if (imageUrl == null || imageUrl.isBlank()) {
            throw new LlmException("智谱响应中没有生成图片地址");
        }
        return downloadImage(imageUrl);
    }

    @Override
    public Optional<IntentResult> classify(String text) {
        JsonNode response = executeJson(properties.chatEndpoint(), buildIntentPayload(text));
        String output = extractOutputText(response);
        if (output == null || output.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode intent = objectMapper.readTree(stripJsonFence(output));
            IntentType type = IntentType.valueOf(intent.path("intent").asText("CHAT"));
            if (type != IntentType.CHAT
                    && type != IntentType.WEATHER
                    && type != IntentType.IMAGE_GENERATION
                    && type != IntentType.IMAGE_ANALYSIS_REQUEST) {
                return Optional.empty();
            }
            WeatherPeriod period;
            try {
                period = WeatherPeriod.valueOf(intent.path("period").asText("CURRENT"));
            } catch (IllegalArgumentException exception) {
                period = WeatherPeriod.CURRENT;
            }
            return Optional.of(new IntentResult(
                    type,
                    intent.path("location").asText(),
                    period,
                    intent.path("prompt").asText()));
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            LOGGER.warn("智谱意图分类返回了无效 JSON：{}", exception.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public String transcribe(VoiceInput input) {
        if (input.hasTranscript()) {
            return input.transcript();
        }
        try {
            PreparedAudio audio = audioConverter.prepareForAsr(input);
            JsonNode response = executeMultipartJson(properties.asrEndpoint(), audio);
            String text = response.path("text").asText();
            if (text.isBlank()) {
                text = extractOutputText(response);
            }
            if (text == null || text.isBlank()) {
                throw new SpeechRecognitionException("智谱 ASR 响应中没有转写文字");
            }
            return text.strip();
        } catch (SpeechRecognitionException exception) {
            throw exception;
        } catch (LlmException exception) {
            throw new SpeechRecognitionException("智谱语音识别失败", exception);
        }
    }

    ObjectNode buildChatPayload(ChatRequest request) {
        return buildChatPayload(request, request.images().isEmpty()
                ? properties.textModel()
                : properties.visionModel());
    }

    private ObjectNode buildChatPayload(ChatRequest request, String model) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model);

        if (request.images().isEmpty() && !toolRegistry.definitions().isEmpty()) {
            ArrayNode tools = root.putArray("tools");
            for (ToolDefinition definition : toolRegistry.definitions()) {
                tools.add(definition.toApiJson(objectMapper));
            }
            root.put("tool_choice", "auto");
        }

        ArrayNode messages = root.putArray("messages");
        messages.addObject()
                .put("role", "system")
                .put("content", SYSTEM_INSTRUCTIONS);
        appendHistory(messages, request.history());

        ObjectNode current = messages.addObject();
        current.put("role", "user");
        String text = request.text().isBlank()
                ? "请识别并描述图片内容；如果图片中包含文字，请一并提取。"
                : request.text();
        if (request.images().isEmpty()) {
            current.put("content", text);
            return root;
        }

        ArrayNode content = current.putArray("content");
        for (ImageInput image : request.images()) {
            String dataUrl = "data:" + image.mediaType() + ";base64,"
                    + Base64.getEncoder().encodeToString(image.data());
            content.addObject()
                    .put("type", "image_url")
                    .putObject("image_url")
                    .put("url", dataUrl);
        }
        content.addObject()
                .put("type", "text")
                .put("text", text);
        return root;
    }

    private List<ModelToolCall> extractToolCalls(JsonNode response) {
        JsonNode calls = response.path("choices").path(0).path("message").path("tool_calls");
        if (!calls.isArray() || calls.isEmpty()) {
            return List.of();
        }
        if (calls.size() > MAX_TOOL_CALLS_PER_ROUND) {
            throw new LlmException("模型单轮请求的工具数量超过限制");
        }
        List<ModelToolCall> result = new ArrayList<>(calls.size());
        for (JsonNode call : calls) {
            String id = call.path("id").asText().strip();
            String name = call.path("function").path("name").asText().strip();
            JsonNode argumentsNode = call.path("function").path("arguments");
            String arguments = argumentsNode.isTextual()
                    ? argumentsNode.asText()
                    : argumentsNode.isObject() ? argumentsNode.toString() : "{}";
            if (id.isBlank() || name.isBlank()) {
                throw new LlmException("模型返回了无效的工具调用");
            }
            result.add(new ModelToolCall(id, name, arguments));
        }
        return List.copyOf(result);
    }

    private void appendAssistantToolRequest(ObjectNode payload, JsonNode response) {
        JsonNode message = response.path("choices").path(0).path("message");
        if (!message.isObject()) {
            throw new LlmException("模型工具调用缺少 assistant 消息");
        }
        ObjectNode assistant = ((ObjectNode) message).deepCopy();
        assistant.put("role", "assistant");
        payload.withArray("messages").add(assistant);
    }

    List<String> candidateChatModels(ChatRequest request) {
        if (request.images().isEmpty()) {
            List<String> fallbackModels = properties.textFallbackModels() == null
                    ? List.of()
                    : properties.textFallbackModels();
            return distinctModels(properties.textModel(), fallbackModels);
        }
        List<String> fallbackModels = properties.visionFallbackModels() == null
                ? List.of()
                : properties.visionFallbackModels();
        return distinctModels(properties.visionModel(), fallbackModels);
    }

    private List<String> distinctModels(String primaryModel, List<String> fallbackModels) {
        return Stream.concat(Stream.of(primaryModel), fallbackModels.stream())
                .filter(model -> model != null && !model.isBlank())
                .map(String::strip)
                .distinct()
                .toList();
    }

    ObjectNode buildImagePayload(List<ChatMessage> history, String prompt) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", properties.imageModel());
        root.put("prompt", contextualizeImagePrompt(history, prompt));
        root.put("size", properties.imageSize());
        return root;
    }

    ObjectNode buildIntentPayload(String text) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", properties.textModel());
        root.putObject("thinking").put("type", "disabled");
        root.putObject("response_format").put("type", "json_object");
        ArrayNode messages = root.putArray("messages");
        messages.addObject().put("role", "system").put("content", INTENT_INSTRUCTIONS);
        messages.addObject().put("role", "user").put("content", text);
        return root;
    }

    String extractOutputText(JsonNode response) {
        JsonNode content = response.path("choices").path(0).path("message").path("content");
        if (content.isTextual()) {
            return content.asText();
        }
        if (!content.isArray()) {
            return null;
        }
        StringBuilder answer = new StringBuilder();
        for (JsonNode part : content) {
            String text = part.path("text").asText();
            if (!text.isBlank()) {
                if (!answer.isEmpty()) {
                    answer.append('\n');
                }
                answer.append(text);
            }
        }
        return answer.isEmpty() ? null : answer.toString();
    }

    String extractGeneratedImageUrl(JsonNode response) {
        JsonNode url = response.path("data").path(0).path("url");
        return url.isTextual() ? url.asText() : null;
    }

    private void appendHistory(ArrayNode messages, List<ChatMessage> history) {
        for (ChatMessage message : history) {
            messages.addObject()
                    .put("role", message.role())
                    .put("content", message.content());
        }
    }

    private String contextualizeImagePrompt(List<ChatMessage> history, String prompt) {
        if (history.isEmpty()) {
            return prompt;
        }
        int fromIndex = Math.max(0, history.size() - MAX_IMAGE_CONTEXT_MESSAGES);
        StringBuilder context = new StringBuilder("对话背景（仅用于理解指代和风格）：\n");
        for (ChatMessage message : history.subList(fromIndex, history.size())) {
            String role = "assistant".equals(message.role()) ? "助手" : "用户";
            context.append(role).append("：").append(message.content()).append('\n');
            if (context.length() >= MAX_IMAGE_CONTEXT_CHARS) {
                context.setLength(MAX_IMAGE_CONTEXT_CHARS);
                break;
            }
        }
        return context.append("当前图片生成要求：").append(prompt).toString();
    }

    private JsonNode executeJson(URI endpoint, ObjectNode payload) {
        properties.validate();
        String body;
        try {
            body = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new LlmException("无法构造智谱请求", exception);
        }

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(properties.timeout())
                    .header("Authorization", "Bearer " + properties.apiKey())
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            try {
                HttpResponse<String> response = httpClient.send(
                        request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return parseJsonResponse(response.body());
                }
                if (attempt < MAX_ATTEMPTS && isRetryable(response.statusCode())) {
                    pauseBeforeRetry();
                    continue;
                }
                throw new ZhipuHttpException(
                        response.statusCode(),
                        describeStatus(response.statusCode(), response.body()));
            } catch (HttpTimeoutException exception) {
                if (attempt == MAX_ATTEMPTS) {
                    throw new LlmException("智谱接口请求超时", exception);
                }
                pauseBeforeRetry();
            } catch (IOException exception) {
                if (attempt == MAX_ATTEMPTS) {
                    throw new LlmException("无法连接智谱接口", exception);
                }
                pauseBeforeRetry();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new LlmException("智谱接口请求被中断", exception);
            }
        }
        throw new LlmException("智谱接口请求失败");
    }

    private JsonNode executeMultipartJson(URI endpoint, PreparedAudio audio) {
        properties.validate();
        String boundary = "----SummerCamp" + UUID.randomUUID().toString().replace("-", "");
        byte[] body = buildMultipartBody(boundary, audio);
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(properties.timeout())
                    .header("Authorization", "Bearer " + properties.apiKey())
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            try {
                HttpResponse<String> response = httpClient.send(
                        request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return parseJsonResponse(response.body());
                }
                if (attempt < MAX_ATTEMPTS && isRetryable(response.statusCode())) {
                    pauseBeforeRetry();
                    continue;
                }
                throw new ZhipuHttpException(
                        response.statusCode(),
                        describeStatus(response.statusCode(), response.body()));
            } catch (HttpTimeoutException exception) {
                if (attempt == MAX_ATTEMPTS) {
                    throw new LlmException("智谱 ASR 请求超时", exception);
                }
                pauseBeforeRetry();
            } catch (IOException exception) {
                if (attempt == MAX_ATTEMPTS) {
                    throw new LlmException("无法连接智谱 ASR 接口", exception);
                }
                pauseBeforeRetry();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new LlmException("智谱 ASR 请求被中断", exception);
            }
        }
        throw new LlmException("智谱 ASR 请求失败");
    }

    byte[] buildMultipartBody(String boundary, PreparedAudio audio) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeTextPart(body, boundary, "model", properties.asrModel());
        writeTextPart(body, boundary, "stream", "false");
        writeAscii(body, "--" + boundary + "\r\n");
        writeAscii(body, "Content-Disposition: form-data; name=\"file\"; filename=\""
                + audio.fileName() + "\"\r\n");
        writeAscii(body, "Content-Type: " + audio.mediaType() + "\r\n\r\n");
        body.writeBytes(audio.data());
        writeAscii(body, "\r\n--" + boundary + "--\r\n");
        return body.toByteArray();
    }

    private void writeTextPart(
            ByteArrayOutputStream body,
            String boundary,
            String name,
            String value) {
        writeAscii(body, "--" + boundary + "\r\n");
        writeAscii(body, "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        writeAscii(body, value + "\r\n");
    }

    private void writeAscii(ByteArrayOutputStream body, String value) {
        body.writeBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private String stripJsonFence(String text) {
        String normalized = text.strip();
        if (normalized.startsWith("```")) {
            normalized = normalized.replaceFirst("^```(?:json)?\\s*", "")
                    .replaceFirst("\\s*```$", "");
        }
        return normalized;
    }

    private JsonNode parseJsonResponse(String body) {
        try {
            JsonNode response = objectMapper.readTree(body);
            if (response == null || response.isNull()) {
                throw new LlmException("智谱接口返回了空响应");
            }
            return response;
        } catch (JsonProcessingException exception) {
            throw new LlmException("智谱接口返回了无法解析的数据", exception);
        }
    }

    private GeneratedImage downloadImage(String imageUrl) {
        URI uri;
        try {
            uri = URI.create(imageUrl);
        } catch (IllegalArgumentException exception) {
            throw new LlmException("智谱返回了无效的图片地址", exception);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || isPrivateHost(uri.getHost())) {
            throw new LlmException("智谱返回了不支持的图片地址");
        }

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(properties.timeout())
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new LlmException("下载生成图片失败，HTTP 状态码：" + response.statusCode());
            }
            byte[] imageBytes;
            try (InputStream input = response.body()) {
                imageBytes = input.readNBytes(MAX_GENERATED_IMAGE_BYTES + 1);
            }
            if (imageBytes.length == 0 || imageBytes.length > MAX_GENERATED_IMAGE_BYTES) {
                throw new LlmException("智谱生成的图片大小无效");
            }
            String declaredMediaType = response.headers()
                    .firstValue("Content-Type")
                    .map(value -> value.split(";", 2)[0].strip().toLowerCase(Locale.ROOT))
                    .orElse("");
            String detectedMediaType = ImageFormats.detectMime(imageBytes);
            if (declaredMediaType.startsWith("image/")
                    && !canonicalImageMediaType(declaredMediaType).equals(detectedMediaType)) {
                LOGGER.warn(
                        "生成图片响应类型与文件签名不一致，将采用文件签名：声明={}，实际={}",
                        declaredMediaType,
                        detectedMediaType);
            }
            return new GeneratedImage(
                    imageBytes,
                    detectedMediaType,
                    "generated-image." + ImageFormats.extension(detectedMediaType));
        } catch (HttpTimeoutException exception) {
            throw new LlmException("下载智谱生成图片超时", exception);
        } catch (IOException exception) {
            throw new LlmException("无法下载智谱生成图片", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LlmException("下载智谱生成图片被中断", exception);
        }
    }

    private String canonicalImageMediaType(String mediaType) {
        return switch (mediaType) {
            case "image/jpg", "image/pjpeg" -> "image/jpeg";
            case "image/x-png" -> "image/png";
            default -> mediaType;
        };
    }

    private boolean isPrivateHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.equals("localhost") || normalized.equals("::1")
                || normalized.startsWith("127.") || normalized.startsWith("10.")
                || normalized.startsWith("192.168.") || normalized.startsWith("169.254.")) {
            return true;
        }
        if (normalized.startsWith("172.")) {
            String[] parts = normalized.split("\\.");
            if (parts.length > 1) {
                try {
                    int second = Integer.parseInt(parts[1]);
                    return second >= 16 && second <= 31;
                } catch (NumberFormatException ignored) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isRetryable(int statusCode) {
        return statusCode == 429 || statusCode >= 500;
    }

    private boolean canTryFallback(int statusCode) {
        return statusCode == 404 || statusCode == 429 || statusCode >= 500;
    }

    private String describeStatus(int statusCode, String body) {
        String summary = switch (statusCode) {
            case 400 -> "智谱接口拒绝了请求，请检查模型和请求内容";
            case 401 -> "智谱接口认证失败，请检查 ZHIPU_API_KEY";
            case 403 -> "当前智谱账号无权使用所选模型或能力";
            case 404 -> "智谱接口不存在，请检查接口路径和模型名称";
            case 429 -> "智谱接口达到速率限制，请稍后重试";
            default -> "智谱接口请求失败，HTTP 状态码：" + statusCode;
        };
        String providerError = extractProviderError(body);
        return providerError == null ? summary : summary + "（平台信息：" + providerError + "）";
    }

    private String extractProviderError(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            String message = root.path("error").path("message").asText();
            if (message.isBlank()) {
                message = root.path("message").asText();
            }
            if (message.isBlank()) {
                message = root.path("msg").asText();
            }
            if (message.isBlank()) {
                return null;
            }
            String normalized = message.replaceAll("\\s+", " ").strip();
            return normalized.length() <= MAX_PROVIDER_ERROR_LENGTH
                    ? normalized
                    : normalized.substring(0, MAX_PROVIDER_ERROR_LENGTH) + "…";
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private void pauseBeforeRetry() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LlmException("智谱接口重试被中断", exception);
        }
    }

    private record ModelToolCall(String id, String name, String arguments) {
    }

    private static final class ZhipuHttpException extends LlmException {

        private final int statusCode;

        private ZhipuHttpException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }

        private int statusCode() {
            return statusCode;
        }
    }
}
