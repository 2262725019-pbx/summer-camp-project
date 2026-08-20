package com.summercamp.project.message;

import com.summercamp.project.conversation.ConversationMemoryStore;
import com.summercamp.project.intent.IntentRecognizer;
import com.summercamp.project.intent.IntentResult;
import com.summercamp.project.intent.IntentType;
import com.summercamp.project.intent.PendingWeatherRequestStore;
import com.summercamp.project.llm.ChatModelClient;
import com.summercamp.project.llm.ChatOutcome;
import com.summercamp.project.llm.ChatRequest;
import com.summercamp.project.llm.GeneratedImage;
import com.summercamp.project.llm.ImageGenerationClient;
import com.summercamp.project.llm.LlmException;
import com.summercamp.project.speech.SpeechRecognitionException;
import com.summercamp.project.speech.SpeechToTextClient;
import com.summercamp.project.speech.SynthesizedSpeech;
import com.summercamp.project.speech.TextToSpeechClient;
import com.summercamp.project.speech.VoiceInput;
import com.summercamp.project.tool.ToolContext;
import com.summercamp.project.weather.WeatherClient;
import com.summercamp.project.weather.WeatherException;
import com.summercamp.project.weather.WeatherLocationAmbiguousException;
import com.summercamp.project.weather.WeatherLocationNotFoundException;
import com.summercamp.project.weather.WeatherPeriod;
import com.summercamp.project.weather.WeatherReport;
import com.summercamp.project.wechat.InboundMessage;
import com.summercamp.project.wechat.WechatGateway;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class MessageProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(MessageProcessor.class);
    private static final int TTS_CHUNK_MAX_CHARS = 1_000;
    private static final String HELP_TEXT = """
            可用功能：
            1. 发送文字：连续对话，机器人会记住最近上下文
            2. 发送语音：自动识别内容，并使用语音回答
            3. 发送图片：识别图片内容，也可以同时附带问题
            4. 询问“宜春袁州区明天天气”：查询高德实时天气或三日预报
            5. 发送“帮我计算125乘36”：由大模型调用本地计算工具
            6. 询问当前时间，或添加、查看和完成个人待办
            7. 要求生成二维码，或连续执行“查天气后给建议”等多步工具任务
            8. /image 图片描述，或说“帮我生成一张图片”：根据描述生成图片
            9. /clear：清除当前用户的对话记录和待补充意图
            10. /help：查看本帮助
            当前版本暂不处理文件和视频。
            """;

    private final WechatGateway gateway;
    private final ChatModelClient chatClient;
    private final ImageGenerationClient imageClient;
    private final SpeechToTextClient speechToTextClient;
    private final TextToSpeechClient textToSpeechClient;
    private final IntentRecognizer intentRecognizer;
    private final WeatherClient weatherClient;
    private final PendingWeatherRequestStore pendingWeatherStore;
    private final ConversationMemoryStore memoryStore;
    private final MessageDeduplicator deduplicator;

    public MessageProcessor(
            WechatGateway gateway,
            ChatModelClient chatClient,
            ImageGenerationClient imageClient,
            SpeechToTextClient speechToTextClient,
            TextToSpeechClient textToSpeechClient,
            IntentRecognizer intentRecognizer,
            WeatherClient weatherClient,
            PendingWeatherRequestStore pendingWeatherStore,
            ConversationMemoryStore memoryStore,
            MessageDeduplicator deduplicator) {
        this.gateway = gateway;
        this.chatClient = chatClient;
        this.imageClient = imageClient;
        this.speechToTextClient = speechToTextClient;
        this.textToSpeechClient = textToSpeechClient;
        this.intentRecognizer = intentRecognizer;
        this.weatherClient = weatherClient;
        this.pendingWeatherStore = pendingWeatherStore;
        this.memoryStore = memoryStore;
        this.deduplicator = deduplicator;
    }

    public void process(InboundMessage message) {
        if (!deduplicator.firstSeen(message.messageId())) {
            LOGGER.debug("忽略重复消息：{}", message.messageId());
            return;
        }
        try {
            route(message);
        } catch (SpeechRecognitionException exception) {
            LOGGER.error("语音识别失败：{}", exception.getMessage());
            safeSendReply(message, "没有听清这段语音，请发送 30 秒以内的清晰语音后重试。");
        } catch (LlmException exception) {
            LOGGER.error("模型处理消息失败：{}", exception.getMessage());
            String reply = message.images().isEmpty()
                    ? "抱歉，模型暂时无法完成这次请求，请稍后再试。"
                    : "图片识别服务当前繁忙，请稍后重新发送图片。";
            safeSendReply(message, reply);
        } catch (IOException exception) {
            LOGGER.error("微信消息发送失败：{}", exception.getMessage());
        } catch (RuntimeException exception) {
            LOGGER.error("处理微信消息时发生未预期错误", exception);
            safeSendText(message.userId(), "抱歉，处理消息时发生错误，请稍后再试。");
        }
    }

    private void route(InboundMessage original) throws IOException {
        if (original.imageTooLarge()) {
            gateway.sendText(original.userId(), "图片超过允许大小，请压缩后重新发送。");
            return;
        }
        if (original.voiceTooLarge()) {
            gateway.sendText(original.userId(), "语音超过 30 秒或 25 MB，请缩短后重新发送。");
            return;
        }
        if (!original.hasSupportedContent()) {
            String reply = original.unsupportedMedia()
                    ? "当前版本暂不支持该语音编码、文件或视频，请先发送文字、图片或普通微信语音。"
                    : "没有识别到可处理的消息内容。";
            gateway.sendText(original.userId(), reply);
            return;
        }

        InboundMessage message = transcribeVoices(original);
        if (!message.images().isEmpty()) {
            answer(message);
            return;
        }

        String command = message.text().strip();
        IntentResult intent = intentRecognizer.recognize(command);
        if (intent.type() == IntentType.CLEAR_CONTEXT) {
            memoryStore.clear(message.userId());
            pendingWeatherStore.clear(message.userId());
            sendReply(message, "已清除你的对话上下文和待处理请求。");
            return;
        }
        if (intent.type() == IntentType.HELP) {
            sendReply(message, HELP_TEXT);
            return;
        }

        if (intent.type() == IntentType.CHAT) {
            Optional<WeatherPeriod> pending = pendingWeatherStore.consume(message.userId());
            if (pending.isPresent()) {
                if (isCancellation(command)) {
                    sendReply(message, "已取消天气查询。");
                } else {
                    answerWeatherWithTools(message, command, pending.get());
                }
                return;
            }
        }

        switch (intent.type()) {
            case WEATHER -> answerWeatherWithTools(message, intent.location(), intent.weatherPeriod());
            case IMAGE_GENERATION -> generateImage(message, intent.prompt());
            case IMAGE_ANALYSIS_REQUEST -> sendReply(
                    message,
                    "可以，请发送需要识别的图片，也可以同时附带问题；收到后我会自动分析图片内容。");
            case CHAT -> answer(message);
            case CLEAR_CONTEXT, HELP -> throw new IllegalStateException("命令意图未被提前处理");
        }
    }

    private InboundMessage transcribeVoices(InboundMessage message) {
        if (message.voices().isEmpty()) {
            return message;
        }
        StringBuilder text = new StringBuilder(message.text().strip());
        for (VoiceInput voice : message.voices()) {
            String transcript = speechToTextClient.transcribe(voice);
            if (!transcript.isBlank()) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(transcript.strip());
            }
        }
        if (text.isEmpty()) {
            throw new SpeechRecognitionException("语音中没有识别到文字");
        }
        return message.withText(text.toString());
    }

    private void answer(InboundMessage message) throws IOException {
        answer(message, message.text());
    }

    private void answer(InboundMessage message, String originalUserText) throws IOException {
        List<com.summercamp.project.llm.ChatMessage> history = memoryStore.history(message.userId());
        ChatRequest request = new ChatRequest(
                history,
                message.text(),
                message.images());
        ChatOutcome outcome = chatClient.chat(
                request,
                new ToolContext(message.userId(), originalUserText, history));
        sendOutcome(message, outcome);

        String memoryText = originalUserText.strip();
        if (!message.images().isEmpty()) {
            String imageMarker = "[用户发送了 " + message.images().size() + " 张图片]";
            memoryText = memoryText.isBlank() ? imageMarker : memoryText + "\n" + imageMarker;
        }
        String memoryReply = outcome.text().isBlank() && !outcome.media().isEmpty()
                ? "[已发送 " + outcome.media().size() + " 张图片]"
                : outcome.text();
        memoryStore.recordExchange(message.userId(), memoryText, memoryReply);
    }

    private void sendOutcome(InboundMessage message, ChatOutcome outcome) throws IOException {
        if (!outcome.text().isBlank()) {
            sendReply(message, outcome.text());
        }
        for (ChatOutcome.Media media : outcome.media()) {
            gateway.sendImage(
                    message.userId(),
                    media.data(),
                    media.fileName(),
                    media.caption());
        }
        if (outcome.text().isBlank() && outcome.media().isEmpty()) {
            throw new LlmException("模型没有返回文字或媒体结果");
        }
    }

    private void answerWeatherWithTools(
            InboundMessage message,
            String location,
            WeatherPeriod period) throws IOException {
        if (location == null || location.isBlank()) {
            pendingWeatherStore.remember(message.userId(), period);
            sendReply(message, "请告诉我需要查询的城市或区县，例如：江西省宜春市袁州区。");
            return;
        }
        InboundMessage toolMessage = message.withText(weatherToolRequest(location, period));
        try {
            answer(toolMessage, message.text());
        } catch (LlmException exception) {
            LOGGER.warn("天气 Function Calling 失败，改用本地高德天气流程：{}", exception.getMessage());
            answerWeatherDirect(message, location, period);
        }
    }

    private String weatherToolRequest(String location, WeatherPeriod period) {
        String periodText = switch (period) {
            case CURRENT -> "当前实时";
            case TODAY -> "今天";
            case TOMORROW -> "明天";
            case DAY_AFTER_TOMORROW -> "后天";
            case THREE_DAYS -> "未来三天";
        };
        return "请调用 get_weather 工具查询%s的%s天气，并严格依据工具结果回答。"
                .formatted(location.strip(), periodText);
    }

    private void answerWeatherDirect(
            InboundMessage message,
            String location,
            WeatherPeriod period) throws IOException {
        try {
            WeatherReport report = weatherClient.query(location, period);
            String answer = report.formatChinese();
            sendReply(message, answer);
            memoryStore.recordExchange(message.userId(), message.text().strip(), answer);
        } catch (WeatherLocationAmbiguousException exception) {
            pendingWeatherStore.remember(message.userId(), period);
            LOGGER.warn("天气地点存在歧义：{}", location);
            sendReply(message, "找到多个同名地区，请补充省和市后再发送，例如：江西省宜春市袁州区。");
        } catch (WeatherLocationNotFoundException exception) {
            pendingWeatherStore.remember(message.userId(), period);
            LOGGER.warn("没有找到天气地点：{}", location);
            sendReply(message, "没有找到这个地区，请补充省、市或区县的完整名称。");
        } catch (IllegalStateException exception) {
            LOGGER.error("天气配置无效：{}", exception.getMessage());
            sendReply(message, "天气功能尚未配置，请先在本地配置文件中填写高德 Web 服务 Key。");
        } catch (WeatherException exception) {
            LOGGER.error("天气查询失败：{}", exception.getMessage());
            sendReply(message, "天气服务暂时无法完成查询，请稍后再试。");
        }
    }

    private void generateImage(InboundMessage message, String prompt) throws IOException {
        if (prompt.isBlank()) {
            sendReply(message,
                    "可以，请告诉我想生成什么图片。例如：帮我生成一张在月球散步的橘猫图片。");
            return;
        }
        try {
            GeneratedImage image = imageClient.generate(memoryStore.history(message.userId()), prompt);
            gateway.sendImage(
                    message.userId(),
                    image.data(),
                    image.fileName(),
                    "已根据你的描述生成图片");
            memoryStore.recordExchange(
                    message.userId(),
                    "/image " + prompt,
                    "[已生成并发送图片]");
        } catch (LlmException exception) {
            LOGGER.error("图片生成失败：{}", exception.getMessage());
            sendReply(message, "图片生成服务暂时无法完成这次请求，请稍后再试，或换一种描述重新生成。");
        }
    }

    private void sendReply(InboundMessage message, String text) throws IOException {
        if (!message.isVoiceMessage()) {
            gateway.sendText(message.userId(), text);
            return;
        }
        try {
            List<SynthesizedSpeech> speeches = new ArrayList<>();
            for (String chunk : splitForSpeech(text)) {
                speeches.add(textToSpeechClient.synthesize(chunk));
            }
            for (SynthesizedSpeech speech : speeches) {
                gateway.sendVoice(
                        message.userId(),
                        speech.data(),
                        speech.fileName(),
                        speech.durationMillis(),
                        speech.sampleRate(),
                        speech.encodeType(),
                        speech.bitsPerSample(),
                        speech.transcript());
            }
            LOGGER.info("已成功发送 {} 段语音回复", speeches.size());
        } catch (LlmException | IOException exception) {
            LOGGER.warn("语音回复失败，将回退为文字：{}", exception.getMessage());
            gateway.sendText(message.userId(), text);
        }
    }

    List<String> splitForSpeech(String text) {
        List<String> chunks = new ArrayList<>();
        String remaining = text.strip();
        while (remaining.length() > TTS_CHUNK_MAX_CHARS) {
            int split = findSpeechSplit(remaining, TTS_CHUNK_MAX_CHARS);
            chunks.add(remaining.substring(0, split).strip());
            remaining = remaining.substring(split).strip();
        }
        if (!remaining.isBlank()) {
            chunks.add(remaining);
        }
        return chunks;
    }

    private int findSpeechSplit(String text, int maximum) {
        for (int index = maximum; index >= maximum / 2; index--) {
            char value = text.charAt(index - 1);
            if (value == '。' || value == '！' || value == '？'
                    || value == '；' || value == '\n') {
                return index;
            }
        }
        return maximum;
    }

    private boolean isCancellation(String command) {
        return "取消".equals(command) || "算了".equals(command) || "不用了".equals(command);
    }

    private void safeSendText(String userId, String text) {
        try {
            gateway.sendText(userId, text);
        } catch (IOException exception) {
            LOGGER.error("发送错误提示失败：{}", exception.getMessage());
        }
    }

    private void safeSendReply(InboundMessage message, String text) {
        try {
            sendReply(message, text);
        } catch (IOException exception) {
            LOGGER.error("发送错误提示失败：{}", exception.getMessage());
        }
    }
}
