package com.summercamp.project.message;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.summercamp.project.conversation.InMemoryConversationMemoryStore;
import com.summercamp.project.intent.IntentClassificationClient;
import com.summercamp.project.intent.IntentRecognizer;
import com.summercamp.project.intent.IntentResult;
import com.summercamp.project.intent.PendingWeatherRequestStore;
import com.summercamp.project.llm.ChatMessage;
import com.summercamp.project.llm.ChatModelClient;
import com.summercamp.project.llm.ChatOutcome;
import com.summercamp.project.llm.ChatRequest;
import com.summercamp.project.llm.GeneratedImage;
import com.summercamp.project.llm.ImageGenerationClient;
import com.summercamp.project.llm.ImageInput;
import com.summercamp.project.llm.LlmException;
import com.summercamp.project.speech.SpeechToTextClient;
import com.summercamp.project.speech.SynthesizedSpeech;
import com.summercamp.project.speech.TextToSpeechClient;
import com.summercamp.project.speech.VoiceInput;
import com.summercamp.project.tool.ToolContext;
import com.summercamp.project.weather.CurrentWeather;
import com.summercamp.project.weather.ForecastDay;
import com.summercamp.project.weather.WeatherClient;
import com.summercamp.project.weather.WeatherPeriod;
import com.summercamp.project.weather.WeatherReport;
import com.summercamp.project.wechat.InboundMessage;
import com.summercamp.project.wechat.WechatGateway;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MessageProcessorTest {

    private FakeGateway gateway;
    private FakeModel model;
    private InMemoryConversationMemoryStore memory;
    private MessageProcessor processor;

    @BeforeEach
    void setUp() {
        gateway = new FakeGateway();
        model = new FakeModel();
        memory = new InMemoryConversationMemoryStore();
        processor = new MessageProcessor(
                gateway,
                model,
                model,
                model,
                model,
                new IntentRecognizer(model),
                new FakeWeather(),
                new PendingWeatherRequestStore(),
                memory,
                new MessageDeduplicator());
    }

    @Test
    void shouldCarryPreviousConversationIntoNextRequest() {
        processor.process(textMessage("1", "user-a", "你好"));
        processor.process(textMessage("2", "user-a", "我刚才说了什么？"));

        assertEquals(2, model.chatRequests.size());
        assertTrue(model.chatRequests.getFirst().history().isEmpty());
        assertEquals(2, model.chatRequests.get(1).history().size());
        assertEquals("你好", model.chatRequests.get(1).history().getFirst().content());
        assertEquals(List.of("reply-1", "reply-2"), gateway.sentTexts);
    }

    @Test
    void shouldGenerateImageAndIgnoreDuplicateMessage() {
        InboundMessage command = textMessage("same-id", "user-a", "/image 月球上的橘猫");

        processor.process(command);
        processor.process(command);

        assertEquals(1, model.imagePrompts.size());
        assertEquals("月球上的橘猫", model.imagePrompts.getFirst());
        assertEquals(1, gateway.sentImages.size());
        assertArrayEquals(new byte[] {9, 8, 7}, gateway.sentImages.getFirst());
    }

    @Test
    void shouldGenerateImageFromNaturalLanguage() {
        processor.process(textMessage(
                "natural-image",
                "user-a",
                "那帮我生成一张图片：一只可爱的小猫"));

        assertEquals(List.of("一只可爱的小猫"), model.imagePrompts);
        assertTrue(model.chatRequests.isEmpty());
        assertEquals(1, gateway.sentImages.size());
    }

    @Test
    void shouldUseSpecificReplyWhenImageGenerationFails() {
        model.failImage = true;

        processor.process(textMessage("failed-generation", "user-a", "帮我生成一张图片：一只小猫"));

        assertTrue(gateway.sentImages.isEmpty());
        assertEquals(
                "图片生成服务暂时无法完成这次请求，请稍后再试，或换一种描述重新生成。",
                gateway.sentTexts.getFirst());
    }

    @Test
    void shouldAskForPromptWhenNaturalImageCommandHasNoDescription() {
        processor.process(textMessage("empty-image", "user-a", "帮我生成一张图片"));

        assertTrue(model.imagePrompts.isEmpty());
        assertTrue(gateway.sentTexts.getFirst().startsWith("可以，请告诉我想生成什么图片"));
    }

    @Test
    void shouldKeepOrdinaryQuestionInTextChat() {
        processor.process(textMessage("idiom", "user-a", "画蛇添足是什么意思？"));

        assertEquals(1, model.chatRequests.size());
        assertTrue(model.imagePrompts.isEmpty());
    }

    @Test
    void shouldAskUserToUploadImageWhenRecognitionRequestHasNoImage() {
        processor.process(textMessage("recognize-without-image", "user-a", "帮我识别一张图片"));

        assertTrue(model.chatRequests.isEmpty());
        assertEquals(
                "可以，请发送需要识别的图片，也可以同时附带问题；收到后我会自动分析图片内容。",
                gateway.sentTexts.getFirst());
    }

    @Test
    void shouldSendAttachedImageToVisionModel() {
        processor.process(new InboundMessage(
                "recognize-with-image",
                "user-a",
                "帮我分析一下这张图片",
                List.of(new ImageInput(new byte[] {1, 2, 3}, "image/png")),
                List.of(),
                false,
                false,
                false));

        assertEquals(1, model.chatRequests.size());
        assertEquals(1, model.chatRequests.getFirst().images().size());
        assertEquals("帮我分析一下这张图片", model.chatRequests.getFirst().text());
    }

    @Test
    void shouldHandleImageAnalysisCapabilityQuestionWithoutCallingTheModel() {
        processor.process(textMessage("capability-question", "user-a", "你能识别图片吗？"));

        assertTrue(model.chatRequests.isEmpty());
        assertTrue(model.imagePrompts.isEmpty());
        assertTrue(gateway.sentTexts.getFirst().contains("请发送需要识别的图片"));
    }

    @Test
    void shouldAskForImageDescriptionForGenerationCapabilityQuestion() {
        processor.process(textMessage("generation-capability", "user-a", "你可以帮我生成一张图片吗？"));

        assertTrue(model.chatRequests.isEmpty());
        assertTrue(model.imagePrompts.isEmpty());
        assertTrue(gateway.sentTexts.getFirst().startsWith("可以，请告诉我想生成什么图片"));
    }

    @Test
    void shouldSendGenericReplyWhenTextModelIsUnavailable() {
        model.failChat = true;

        processor.process(textMessage("failed-text", "user-a", "介绍一下你自己"));

        assertEquals(
                "抱歉，模型暂时无法完成这次请求，请稍后再试。",
                gateway.sentTexts.getFirst());
    }

    @Test
    void shouldUseSpecificReplyWhenImageRecognitionIsBusy() {
        model.failChat = true;

        processor.process(new InboundMessage(
                "failed-image",
                "user-a",
                "图片中有什么？",
                List.of(new ImageInput(new byte[] {1, 2, 3}, "image/png")),
                List.of(),
                false,
                false,
                false));

        assertEquals("图片识别服务当前繁忙，请稍后重新发送图片。", gateway.sentTexts.getFirst());
    }

    @Test
    void shouldSendVoiceErrorReplyWhenTextModelIsUnavailableForVoiceMessage() {
        model.failChat = true;

        processor.process(new InboundMessage(
                "failed-voice-chat",
                "user-a",
                "",
                List.of(),
                List.of(new VoiceInput(
                        new byte[] {1},
                        "介绍一下你自己",
                        6,
                        16,
                        24_000,
                        1_000)),
                false,
                false,
                false));

        assertEquals(1, gateway.sentVoices.size());
        assertTrue(gateway.sentTexts.isEmpty());
    }

    @Test
    void shouldClearOnlyCurrentUsersHistory() {
        processor.process(textMessage("1", "user-a", "A"));
        processor.process(textMessage("2", "user-b", "B"));
        processor.process(textMessage("3", "user-a", "/clear"));

        assertTrue(memory.history("user-a").isEmpty());
        assertEquals(2, memory.history("user-b").size());
        assertEquals("已清除你的对话上下文和待处理请求。", gateway.sentTexts.getLast());
    }

    @Test
    void shouldTranscribeVoiceAndReplyWithVoice() {
        processor.process(new InboundMessage(
                "voice-1",
                "user-a",
                "",
                List.of(),
                List.of(new VoiceInput(
                        new byte[] {1},
                        "介绍一下你自己",
                        6,
                        16,
                        24_000,
                        1_000)),
                false,
                false,
                false));

        assertEquals("介绍一下你自己", model.chatRequests.getFirst().text());
        assertEquals(1, gateway.sentVoices.size());
        assertTrue(gateway.sentTexts.isEmpty());
    }

    @Test
    void shouldFallBackToTextWhenVoiceSynthesisFails() {
        model.failTts = true;

        processor.process(new InboundMessage(
                "voice-fallback",
                "user-a",
                "",
                List.of(),
                List.of(new VoiceInput(
                        new byte[] {1},
                        "介绍一下你自己",
                        6,
                        16,
                        24_000,
                        1_000)),
                false,
                false,
                false));

        assertTrue(gateway.sentVoices.isEmpty());
        assertEquals(List.of("reply-1"), gateway.sentTexts);
    }

    @Test
    void shouldFallBackToOriginalTextWhenSynthesizedVoiceSendFails() {
        gateway.failVoice = true;

        processor.process(new InboundMessage(
                "voice-send-fallback",
                "user-a",
                "",
                List.of(),
                List.of(new VoiceInput(
                        new byte[] {1},
                        "镇江现在天气怎么样？",
                        6,
                        16,
                        24_000,
                        1_000)),
                false,
                false,
                false));

        assertTrue(gateway.sentVoices.isEmpty());
        assertEquals(List.of("reply-1"), gateway.sentTexts);
    }

    @Test
    void shouldSplitLongSpeechAtChinesePunctuation() {
        String longAnswer = "甲".repeat(700) + "。" + "乙".repeat(700);

        List<String> chunks = processor.splitForSpeech(longAnswer);

        assertEquals(2, chunks.size());
        assertTrue(chunks.getFirst().endsWith("。"));
        assertTrue(chunks.stream().allMatch(chunk -> chunk.length() <= 1_000));
    }

    @Test
    void shouldAskForLocationThenCompleteWeatherRequest() {
        processor.process(textMessage("weather-1", "user-a", "明天天气怎么样"));
        processor.process(textMessage("weather-2", "user-a", "江西省宜春市袁州区"));

        assertTrue(gateway.sentTexts.getFirst().startsWith("请告诉我需要查询的城市"));
        assertEquals("reply-1", gateway.sentTexts.getLast());
        assertEquals(1, model.chatRequests.size());
        assertTrue(model.chatRequests.getFirst().text().contains("get_weather"));
        assertTrue(model.chatRequests.getFirst().text().contains("江西省宜春市袁州区"));
        assertTrue(model.chatRequests.getFirst().text().contains("明天"));
    }

    @Test
    void shouldFallBackToDirectWeatherWhenToolCallingModelFails() {
        model.failChat = true;

        processor.process(textMessage(
                "weather-tool-fallback",
                "user-a",
                "明天江西省宜春市袁州区天气怎么样"));

        assertEquals(1, gateway.sentTexts.size());
        assertTrue(gateway.sentTexts.getFirst().contains("宜春市天气预报"));
    }

    @Test
    void shouldSendTextAndImageProducedByToolCalling() {
        model.nextOutcome = new ChatOutcome(
                "多步任务已完成。",
                List.of(new ChatOutcome.Media(
                        new byte[] {3, 2, 1}, "tool-image.png", "工具生成图片")));

        processor.process(textMessage("tool-image", "user-a", "完成任务并生成图片"));

        assertEquals(List.of("多步任务已完成。"), gateway.sentTexts);
        assertEquals(1, gateway.sentImages.size());
        assertArrayEquals(new byte[] {3, 2, 1}, gateway.sentImages.getFirst());
        assertEquals("user-a", model.lastToolContext.userId());
    }

    private InboundMessage textMessage(String id, String userId, String text) {
        return new InboundMessage(
                id,
                userId,
                text,
                List.of(),
                List.of(),
                false,
                false,
                false);
    }

    private static final class FakeModel implements
            ChatModelClient,
            ImageGenerationClient,
            SpeechToTextClient,
            TextToSpeechClient,
            IntentClassificationClient {

        private final List<ChatRequest> chatRequests = new ArrayList<>();
        private final List<String> imagePrompts = new ArrayList<>();
        private boolean failChat;
        private boolean failImage;
        private boolean failTts;
        private ChatOutcome nextOutcome;
        private ToolContext lastToolContext;

        @Override
        public ChatOutcome chat(ChatRequest request, ToolContext context) {
            lastToolContext = context;
            if (nextOutcome != null) {
                chatRequests.add(request);
                ChatOutcome outcome = nextOutcome;
                nextOutcome = null;
                return outcome;
            }
            if (failChat) {
                throw new LlmException("test failure");
            }
            chatRequests.add(request);
            return ChatOutcome.text("reply-" + chatRequests.size());
        }

        @Override
        public GeneratedImage generate(List<ChatMessage> history, String prompt) {
            imagePrompts.add(prompt);
            if (failImage) {
                throw new LlmException("test image failure");
            }
            return new GeneratedImage(new byte[] {9, 8, 7}, "image/png", "generated.png");
        }

        @Override
        public String transcribe(VoiceInput input) {
            return input.transcript();
        }

        @Override
        public SynthesizedSpeech synthesize(String text) {
            if (failTts) {
                throw new LlmException("test TTS failure");
            }
            return new SynthesizedSpeech(
                    new byte[] {4, 5, 6},
                    "reply.pcm",
                    1_000,
                    24_000,
                    1,
                    16,
                    text);
        }

        @Override
        public Optional<IntentResult> classify(String text) {
            return Optional.empty();
        }
    }

    private static final class FakeGateway implements WechatGateway {

        private final List<String> sentTexts = new ArrayList<>();
        private final List<byte[]> sentImages = new ArrayList<>();
        private final List<byte[]> sentVoices = new ArrayList<>();
        private boolean failVoice;

        @Override
        public void loginAndWait(Path qrCodePath) {
        }

        @Override
        public List<InboundMessage> poll() {
            return List.of();
        }

        @Override
        public void sendText(String userId, String text) {
            sentTexts.add(text);
        }

        @Override
        public void sendImage(String userId, byte[] data, String fileName, String caption) {
            sentImages.add(data.clone());
        }

        @Override
        public void sendVoice(
                String userId,
                byte[] data,
                String fileName,
                int durationMillis,
                int sampleRate,
                int encodeType,
                int bitsPerSample,
                String transcript) throws IOException {
            if (failVoice) {
                throw new IOException("voice send failed");
            }
            sentVoices.add(data.clone());
        }

        @Override
        public void close() {
        }
    }

    private static final class FakeWeather implements WeatherClient {

        @Override
        public WeatherReport query(String location, WeatherPeriod period) {
            if (period == WeatherPeriod.CURRENT) {
                return new WeatherReport(
                        "宜春市",
                        "2026-08-18 11:00:00",
                        period,
                        new CurrentWeather("晴", "30", "55", "东", "2"),
                        List.of());
            }
            ForecastDay day = new ForecastDay(
                    "2026-08-19", "3", "晴", "多云", "32", "24", "东", "东", "2", "1");
            return new WeatherReport(
                    "宜春市",
                    "2026-08-18 11:00:00",
                    period,
                    null,
                    List.of(day));
        }
    }
}
