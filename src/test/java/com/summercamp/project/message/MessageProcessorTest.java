package com.summercamp.project.message;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summercamp.project.agent.AgentGoalMatcher;
import com.summercamp.project.agent.AgentAction;
import com.summercamp.project.agent.AgentActionHandler;
import com.summercamp.project.agent.AgentActionHandlerRegistry;
import com.summercamp.project.agent.AgentExecutionContext;
import com.summercamp.project.agent.AgentExecutor;
import com.summercamp.project.agent.AgentObservation;
import com.summercamp.project.agent.AgentOrchestrator;
import com.summercamp.project.agent.AgentPlan;
import com.summercamp.project.agent.AgentResumeInput;
import com.summercamp.project.agent.AgentRunCheckpoint;
import com.summercamp.project.agent.AgentRunRequest;
import com.summercamp.project.agent.AgentRunResult;
import com.summercamp.project.agent.AgentState;
import com.summercamp.project.agent.AgentStep;
import com.summercamp.project.agent.PendingAgentRunStore;
import com.summercamp.project.config.RagProperties;
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
import com.summercamp.project.rag.KeywordRagRetriever;
import com.summercamp.project.skill.PendingSkillStore;
import com.summercamp.project.skill.SkillRegistry;
import com.summercamp.project.skill.entertainment.ColdJokeSkill;
import com.summercamp.project.skill.nutrition.FoodCatalog;
import com.summercamp.project.skill.nutrition.MuscleGainMealPlanSkill;
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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MessageProcessorTest {

    private FakeGateway gateway;
    private FakeModel model;
    private InMemoryConversationMemoryStore memory;
    private MessageProcessor processor;
    private AgentOrchestrator agentOrchestrator;
    private PendingAgentRunStore pendingAgentRunStore;

    @BeforeEach
    void setUp() {
        gateway = new FakeGateway();
        model = new FakeModel();
        memory = new InMemoryConversationMemoryStore();
        agentOrchestrator = mock(AgentOrchestrator.class);
        pendingAgentRunStore = new PendingAgentRunStore();
        when(agentOrchestrator.run(any(AgentRunRequest.class))).thenReturn(new AgentRunResult(
                AgentRunResult.Status.COMPLETED,
                "agent-final-reply",
                null,
                null
        ));
        ObjectMapper objectMapper = new ObjectMapper();
        SkillRegistry skillRegistry = new SkillRegistry(List.of(
                new MuscleGainMealPlanSkill(new FoodCatalog(objectMapper)),
                new ColdJokeSkill()));
        processor = new MessageProcessor(
                gateway,
                model,
                model,
                model,
                model,
                new IntentRecognizer(model),
                new FakeWeather(),
                new PendingWeatherRequestStore(),
                skillRegistry,
                new PendingSkillStore(),
                new KeywordRagRetriever(new RagProperties(true, 3, 2, 2_500), objectMapper),
                memory,
                new MessageDeduplicator(),
                new AgentGoalMatcher(),
                agentOrchestrator,
                pendingAgentRunStore);
    }

    @Test
    void shouldRunExplicitAgentGoalAndRecordOnlyGoalAndFinalReply() {
        processor.process(textMessage(
                "agent-explicit",
                "user-a",
                "/agent   帮我制定未来7天的运动、饮食和作息综合计划"));

        ArgumentCaptor<AgentRunRequest> request = ArgumentCaptor.forClass(AgentRunRequest.class);
        verify(agentOrchestrator).run(request.capture());
        assertEquals("user-a", request.getValue().userId());
        assertEquals("帮我制定未来7天的运动、饮食和作息综合计划", request.getValue().goal());
        assertTrue(request.getValue().history().isEmpty());
        assertTrue(!request.getValue().voiceMessage());
        assertEquals(List.of("agent-final-reply"), gateway.sentTexts);
        assertEquals(2, memory.history("user-a").size());
        assertEquals(request.getValue().goal(), memory.history("user-a").getFirst().content());
        assertEquals("agent-final-reply", memory.history("user-a").getLast().content());
        assertTrue(model.chatRequests.isEmpty());
    }

    @Test
    void shouldShortCircuitNaturalAgentGoalBeforeSkillRagChatAndWeather() {
        processor.process(textMessage(
                "agent-natural",
                "user-a",
                "帮我制定未来七天运动、增肌饮食和睡眠的完整健康生活计划"));

        verify(agentOrchestrator).run(any(AgentRunRequest.class));
        assertEquals(List.of("agent-final-reply"), gateway.sentTexts);
        assertTrue(model.chatRequests.isEmpty());
        assertTrue(model.imagePrompts.isEmpty());
    }

    @Test
    void shouldPromptForEmptyAgentGoalWithoutCallingOrchestrator() {
        processor.process(textMessage("agent-empty", "user-a", "/agent   "));

        verify(agentOrchestrator, never()).run(any(AgentRunRequest.class));
        assertEquals(List.of(AgentGoalMatcher.EMPTY_GOAL_REPLY), gateway.sentTexts);
        assertTrue(memory.history("user-a").isEmpty());
    }

    @Test
    void shouldRecordRecoverableAgentReplyButNotFailedRun() {
        when(agentOrchestrator.run(any(AgentRunRequest.class)))
                .thenReturn(new AgentRunResult(
                        AgentRunResult.Status.NEEDS_USER_INPUT,
                        "请重新发送包含城市的完整最终目标。",
                        null,
                        null
                ))
                .thenReturn(new AgentRunResult(
                        AgentRunResult.Status.FAILED,
                        "未能完成本次健康生活规划，请稍后重试或调整目标。",
                        null,
                        null
                ));

        processor.process(textMessage("agent-wait", "user-a", "/agent 制定未来一周运动饮食计划"));
        processor.process(textMessage("agent-fail", "user-b", "/agent 制定未来一周运动饮食计划"));

        assertEquals(2, memory.history("user-a").size());
        assertTrue(memory.history("user-b").isEmpty());
        assertEquals(List.of(
                "请重新发送包含城市的完整最终目标。",
                "未能完成本次健康生活规划，请稍后重试或调整目标。"
        ), gateway.sentTexts);
    }

    @Test
    void shouldPassVoiceFlagAndReuseVoiceReplyPipelineForAgent() {
        processor.process(new InboundMessage(
                "agent-voice",
                "user-a",
                "",
                List.of(),
                List.of(new VoiceInput(
                        new byte[] {1},
                        "/agent 制定未来七天运动饮食和作息计划",
                        6,
                        16,
                        24_000,
                        1_000)),
                false,
                false,
                false));

        ArgumentCaptor<AgentRunRequest> request = ArgumentCaptor.forClass(AgentRunRequest.class);
        verify(agentOrchestrator).run(request.capture());
        assertTrue(request.getValue().voiceMessage());
        assertEquals(1, gateway.sentVoices.size());
        assertTrue(gateway.sentTexts.isEmpty());
    }

    @Test
    void shouldResumePendingAgentBeforeChatAndRecordOnlyLatestSupplement() {
        String goal = "制定未来七天运动饮食和作息计划";
        AgentRunResult waiting = waitingAgentResult(goal);
        when(agentOrchestrator.run(any(AgentRunRequest.class))).thenReturn(waiting);
        when(agentOrchestrator.resume(
                any(AgentRunCheckpoint.class),
                any(String.class),
                any(AgentResumeInput.class),
                any(List.class),
                org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(new AgentRunResult(
                        AgentRunResult.Status.COMPLETED,
                        "resume-final-reply",
                        waiting.plan(),
                        waiting.state()));

        processor.process(textMessage("agent-first", "user-a", "/agent " + goal));
        String supplement = "性别：男\n年龄：22\n每日餐数：4餐";
        processor.process(textMessage("agent-resume", "user-a", supplement));

        ArgumentCaptor<AgentResumeInput> input = ArgumentCaptor.forClass(AgentResumeInput.class);
        verify(agentOrchestrator).resume(
                any(AgentRunCheckpoint.class),
                org.mockito.ArgumentMatchers.eq("user-a"),
                input.capture(),
                any(List.class),
                org.mockito.ArgumentMatchers.eq(false));
        assertEquals("meal", input.getValue().waitingStepId());
        assertEquals(supplement, input.getValue().supplementText());
        assertEquals(1, input.getValue().attempt());
        assertTrue(model.chatRequests.isEmpty());
        assertTrue(pendingAgentRunStore.get("user-a").isEmpty());
        List<ChatMessage> history = memory.history("user-a");
        assertEquals(4, history.size());
        assertEquals(goal, history.get(0).content());
        assertEquals(supplement, history.get(2).content());
        assertEquals(1, history.stream().filter(item -> goal.equals(item.content())).count());
    }

    @Test
    void shouldCancelOrClearPendingAgentWithoutResume() {
        String goal = "制定未来七天健康计划";
        when(agentOrchestrator.run(any(AgentRunRequest.class)))
                .thenReturn(waitingAgentResult(goal));

        processor.process(textMessage("agent-cancel-start", "user-a", "/agent " + goal));
        processor.process(textMessage("agent-cancel", "user-a", "算了"));
        assertTrue(pendingAgentRunStore.get("user-a").isEmpty());
        assertEquals("已取消待补充的 Agent 规划。", gateway.sentTexts.getLast());

        processor.process(textMessage("agent-clear-start", "user-a", "/agent " + goal));
        processor.process(textMessage("agent-clear", "user-a", "/clear"));
        assertTrue(pendingAgentRunStore.get("user-a").isEmpty());
        assertTrue(memory.history("user-a").isEmpty());
        verify(agentOrchestrator, never()).resume(
                any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void newAgentReplacesPendingCheckpointAndNonChatDoesNotConsumeIt() {
        String firstGoal = "制定未来七天健康计划";
        AgentRunResult firstWaiting = waitingAgentResult(firstGoal);
        AgentRunResult completed = new AgentRunResult(
                AgentRunResult.Status.COMPLETED, "new-agent-final", null, null);
        when(agentOrchestrator.run(any(AgentRunRequest.class)))
                .thenReturn(firstWaiting)
                .thenReturn(completed);

        processor.process(textMessage("agent-old", "user-a", "/agent " + firstGoal));
        processor.process(textMessage("weather-non-chat", "user-a", "明天宜春天气怎么样"));

        assertTrue(pendingAgentRunStore.get("user-a").isPresent());
        verify(agentOrchestrator, never()).resume(
                any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());

        processor.process(textMessage("agent-new", "user-a", "/agent 制定新的完整健康目标"));
        assertTrue(pendingAgentRunStore.get("user-a").isEmpty());
        verify(agentOrchestrator, times(2)).run(any(AgentRunRequest.class));
    }

    @Test
    void secondNeedsInputRefreshesCheckpointAndFourthSupplementHitsAttemptCap() {
        String goal = "制定未来七天健康计划";
        AgentRunResult waiting = waitingAgentResult(goal);
        when(agentOrchestrator.run(any(AgentRunRequest.class))).thenReturn(waiting);
        when(agentOrchestrator.resume(
                any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(waiting);

        processor.process(textMessage("agent-cap-start", "user-a", "/agent " + goal));
        processor.process(textMessage("agent-cap-1", "user-a", "补充一"));
        assertEquals(1, pendingAgentRunStore.get("user-a").orElseThrow().resumeAttemptCount());
        processor.process(textMessage("agent-cap-2", "user-a", "补充二"));
        processor.process(textMessage("agent-cap-3", "user-a", "补充三"));
        assertEquals(3, pendingAgentRunStore.get("user-a").orElseThrow().resumeAttemptCount());

        processor.process(textMessage("agent-cap-4", "user-a", "补充四"));

        verify(agentOrchestrator, times(3)).resume(
                any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        assertTrue(pendingAgentRunStore.get("user-a").isEmpty());
        assertEquals("补充信息仍不足，本次规划已结束，请重新发起完整目标。", gateway.sentTexts.getLast());
    }

    @Test
    void providerFailureDoesNotCreateCheckpointAndHelpDoesNotConsumeExistingOne() {
        when(agentOrchestrator.run(any(AgentRunRequest.class))).thenReturn(new AgentRunResult(
                AgentRunResult.Status.FAILED, "provider failed", null, null));
        processor.process(textMessage("agent-provider-fail", "user-a", "/agent 制定未来七天健康计划"));
        assertTrue(pendingAgentRunStore.get("user-a").isEmpty());

        AgentRunResult waiting = waitingAgentResult("另一个健康目标");
        pendingAgentRunStore.rememberInitial(
                "user-a",
                new AgentRunRequest("user-a", "另一个健康目标", List.of(), false),
                waiting);
        processor.process(textMessage("agent-help", "user-a", "/help"));
        assertTrue(pendingAgentRunStore.get("user-a").isPresent());
    }

    @Test
    void voiceSupplementResumesTypedAgentAfterTranscription() {
        String goal = "制定未来七天健康计划";
        AgentRunResult waiting = waitingAgentResult(goal);
        when(agentOrchestrator.run(any(AgentRunRequest.class))).thenReturn(waiting);
        when(agentOrchestrator.resume(
                any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(new AgentRunResult(
                        AgentRunResult.Status.COMPLETED, "voice-resume-final", null, null));
        processor.process(textMessage("agent-voice-start", "user-a", "/agent " + goal));

        processor.process(new InboundMessage(
                "agent-voice-resume",
                "user-a",
                "",
                List.of(),
                List.of(new VoiceInput(
                        new byte[] {1},
                        "性别男，年龄22岁，每日4餐",
                        6, 16, 24_000, 1_000)),
                false,
                false,
                false));

        ArgumentCaptor<AgentResumeInput> input = ArgumentCaptor.forClass(AgentResumeInput.class);
        verify(agentOrchestrator).resume(
                any(), any(), input.capture(), any(), org.mockito.ArgumentMatchers.eq(true));
        assertEquals("性别男，年龄22岁，每日4餐", input.getValue().supplementText());
        assertEquals(1, gateway.sentVoices.size());
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
        assertTrue(model.chatRequests.getFirst().groundingContext().isBlank());
    }

    @Test
    void shouldCompleteMealPlanSkillAcrossTwoMessagesWithoutCallingLlm() {
        processor.process(textMessage("meal-1", "user-a", "帮我制定一个增肌饮食计划"));
        processor.process(textMessage("meal-2", "user-a", """
                性别：男
                年龄：22
                身高：175cm
                体重：70kg
                日常活动：重度
                每周训练：4次
                每次训练：60分钟
                每日餐数：4餐
                健康确认：健康成人、无食物过敏
                """));

        assertTrue(model.chatRequests.isEmpty());
        assertTrue(gateway.sentTexts.getFirst().contains("请按下面格式补充完整资料"));
        assertTrue(gateway.sentTexts.getLast().contains("训练日目标"));
        assertTrue(gateway.sentTexts.getLast().contains("休息日目标"));
        assertEquals(4, memory.history("user-a").size());
    }

    @Test
    void shouldExecuteColdJokeSkillWithoutCallingLlm() {
        processor.process(textMessage("joke-1", "user-a", "给我讲个冷笑话"));

        assertTrue(model.chatRequests.isEmpty());
        assertTrue(gateway.sentTexts.getFirst().startsWith("给你讲一个冷笑话："));
        assertEquals(2, memory.history("user-a").size());
    }

    @Test
    void shouldAugmentProjectFaqQuestionWithRagContext() {
        processor.process(textMessage("rag-1", "user-a", "智谱 API Key 应该配置在哪里？"));

        assertEquals(1, model.chatRequests.size());
        ChatRequest request = model.chatRequests.getFirst();
        assertEquals("智谱 API Key 应该配置在哪里？", request.text());
        assertTrue(request.groundingContext().contains("config/application-local.properties"));
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
    void shouldSplitLongSpeechAtChinesePunctuation() {
        String longAnswer = "甲".repeat(700) + "。" + "乙".repeat(700);

        List<String> chunks = processor.splitForSpeech(longAnswer);

        assertEquals(2, chunks.size());
        assertTrue(chunks.getFirst().endsWith("。"));
        assertTrue(chunks.stream().allMatch(chunk -> chunk.length() <= 1_000));
    }

    @Test
    void shouldRemoveMarkdownMarkersBeforeSpeechSynthesis() {
        String answer = """
                ## **功能介绍**
                * **文字聊天**：回答日常问题
                * 支持 *语音回复*
                * 计算 2 * 3
                详情请看[使用说明](https://example.com/help)。
                """;

        List<String> chunks = processor.splitForSpeech(answer);

        assertEquals(List.of("""
                功能介绍
                文字聊天：回答日常问题
                支持 语音回复
                计算 2 * 3
                详情请看使用说明。
                """.strip()), chunks);
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

    private AgentRunResult waitingAgentResult(String goal) {
        AgentPlan plan = new AgentPlan(goal, List.of(
                new AgentStep("datetime", AgentAction.GET_DATETIME,
                        "日期", "锚定", List.of()),
                new AgentStep("meal", AgentAction.RUN_MEAL_SKILL,
                        "饮食", "补资料", List.of("datetime")),
                new AgentStep("validate", AgentAction.VALIDATE,
                        "校验", "闭环", List.of("meal")),
                new AgentStep("synthesis", AgentAction.SYNTHESIZE,
                        "汇总", "输出", List.of("validate"))));
        AgentActionHandler datetime = new AgentActionHandler() {
            @Override
            public AgentAction action() {
                return AgentAction.GET_DATETIME;
            }

            @Override
            public AgentObservation execute(AgentStep step, AgentExecutionContext context) {
                return new AgentObservation(step.id(), true, "日期完成");
            }
        };
        AgentActionHandler meal = new AgentActionHandler() {
            @Override
            public AgentAction action() {
                return AgentAction.RUN_MEAL_SKILL;
            }

            @Override
            public AgentObservation execute(AgentStep step, AgentExecutionContext context) {
                return new AgentObservation(step.id(), false, "请补资料", Map.of(
                        "code", "NEEDS_USER_INPUT", "recoverable", "true"));
            }
        };
        AgentState state = new AgentExecutor(new AgentActionHandlerRegistry(List.of(datetime, meal)))
                .execute("user-a", goal, List.of(), false, plan);
        return new AgentRunResult(
                AgentRunResult.Status.NEEDS_USER_INPUT, "请补资料", plan, state);
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
                String transcript) {
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
