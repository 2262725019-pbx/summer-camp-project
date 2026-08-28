package com.summercamp.project.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

class ConversationMemoryRecallEvaluationTest {

    @ParameterizedTest(name = "exact recall: {1}")
    @MethodSource("exactRecallCases")
    void shouldRecallExactHistoricalExchange(String earlyText, String query, String expected) {
        InMemoryConversationMemoryStore store = agedMemory(earlyText);

        MemoryContext context = store.recall("user", query);

        assertTopOneContains(context, expected);
    }

    @ParameterizedTest(name = "paraphrase recall: {1}")
    @MethodSource("paraphraseRecallCases")
    void shouldRecallParaphrasedHistoricalExchange(String earlyText, String query, String expected) {
        InMemoryConversationMemoryStore store = agedMemory(earlyText);

        MemoryContext context = store.recall("user", query);

        assertTopOneContains(context, expected);
    }

    @ParameterizedTest(name = "negative recall: {0}")
    @MethodSource("negativeRecallCases")
    void shouldNotRecallUnrelatedHistory(String query) {
        InMemoryConversationMemoryStore store = new InMemoryConversationMemoryStore();
        store.recordExchange("user", "我喜欢使用 Java", "已经记下你的开发偏好。");
        store.recordExchange("user", "项目使用本地 RAG", "了解项目架构。");
        store.recordExchange("user", "这是一个微信机器人", "好的。");
        addFillers(store, "user");

        MemoryContext context = store.recall("user", query);

        assertTrue(context.recalledEntries().isEmpty(), () -> "Unexpected recall: " + context.recalledEntries());
        assertTrue(context.promptContext().isBlank());
    }

    @Test
    void shouldLetOldRelevantMemoryBeatRecentIrrelevantConversation() {
        InMemoryConversationMemoryStore store = agedMemory("我的答辩重点是断点续跑。");

        MemoryContext context = store.recall("user", "答辩重点是什么？");

        assertTopOneContains(context, "断点续跑");
        assertFalse(context.recentMessages().stream()
                .anyMatch(message -> message.content().contains("断点续跑")));
    }

    @Test
    void shouldNotRecallAnExchangeAlreadyInRecentWindow() {
        InMemoryConversationMemoryStore store = new InMemoryConversationMemoryStore();
        store.recordExchange("user", "我的答辩重点是断点续跑。", "记住了。");

        MemoryContext context = store.recall("user", "答辩重点是什么？");

        assertTrue(context.recalledEntries().isEmpty());
        assertTrue(context.recentMessages().stream()
                .anyMatch(message -> message.content().contains("断点续跑")));
    }

    @Test
    void shouldNeverRecallAnotherUsersMemory() {
        InMemoryConversationMemoryStore store = new InMemoryConversationMemoryStore();
        store.recordExchange("user-a", "我最喜欢用 Java 做后端。", "记住了。");
        addFillers(store, "user-a");
        store.recordExchange("user-b", "你好", "你好！");
        addFillers(store, "user-b");

        MemoryContext context = store.recall("user-b", "我之前偏好什么后端语言？");

        assertTrue(context.recalledEntries().isEmpty());
        assertFalse(context.promptContext().contains("Java"));
    }

    static Stream<Arguments> exactRecallCases() {
        return Stream.of(
                Arguments.of("我的答辩重点是断点续跑。", "我刚才说答辩重点是什么？", "断点续跑"),
                Arguments.of("这个项目代号是星河。", "项目代号是什么？", "星河"),
                Arguments.of("会议安排在周五下午。", "会议安排在什么时候？", "周五下午"),
                Arguments.of("文档放在共享目录。", "共享目录里的文档放在哪里？", "共享目录"));
    }

    static Stream<Arguments> paraphraseRecallCases() {
        return Stream.of(
                Arguments.of("我最喜欢用 Java 做后端。", "我之前说过偏好什么后端语言？", "Java"),
                Arguments.of(
                        "演示时我希望先展示基础功能，再演示高级功能。",
                        "演示顺序还是按我之前说的吗？",
                        "先展示基础功能"),
                Arguments.of("数据库使用 PostgreSQL。", "之前选的数据库是什么？", "PostgreSQL"),
                Arguments.of("演示地点写镇江。", "我先前安排的演示位置是哪儿？", "镇江"));
    }

    static Stream<String> negativeRecallCases() {
        return Stream.of(
                "推荐一部科幻电影。",
                "今天镇江下雨吗？",
                "125*36 等于多少？",
                "今晚吃什么比较好？");
    }

    private InMemoryConversationMemoryStore agedMemory(String earlyText) {
        InMemoryConversationMemoryStore store = new InMemoryConversationMemoryStore();
        store.recordExchange("user", earlyText, "好的，我记住了。");
        addFillers(store, "user");
        return store;
    }

    private void addFillers(InMemoryConversationMemoryStore store, String userId) {
        for (int index = 0; index < 6; index++) {
            store.recordExchange(
                    userId,
                    "无关的日常闲聊编号 " + index,
                    "这是普通回复编号 " + index);
        }
    }

    private void assertTopOneContains(MemoryContext context, String expected) {
        assertFalse(context.recalledEntries().isEmpty(), () -> "Expected recall, got: " + context);
        assertTrue(context.recalledEntries().getFirst().entry().userText().contains(expected),
                () -> "Unexpected top1: " + context.recalledEntries());
        assertEquals("CONVERSATION_SESSION", context.recalledEntries().getFirst().source());
        assertEquals(context.recalledEntries().size(), context.diagnostics().memoryRecalledEntries());
    }
}
