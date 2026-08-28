package com.summercamp.project.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.summercamp.project.llm.ChatMessage;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InMemoryConversationMemoryStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldIsolateUsersAndCompressOldestMessagesIntoSummary() {
        MutableClock clock = new MutableClock();
        InMemoryConversationMemoryStore store = new InMemoryConversationMemoryStore(clock);

        for (int index = 0; index < 12; index++) {
            store.recordExchange("user-a", "question-" + index, "answer-" + index);
        }
        store.recordExchange("user-b", "other-question", "other-answer");

        List<ChatMessage> historyA = store.history("user-a");
        // 12 轮 = 24 条，压缩到最近 10 条 + 一条较早对话摘要
        assertEquals(11, historyA.size());
        assertEquals("system", historyA.getFirst().role());
        assertTrue(historyA.getFirst().content().contains("较早的对话（已压缩）"));
        assertEquals("answer-11", historyA.getLast().content());
        assertEquals(2, store.history("user-b").size());
    }

    @Test
    void shouldExpireHistoryAfterThirtyMinutes() {
        MutableClock clock = new MutableClock();
        InMemoryConversationMemoryStore store = new InMemoryConversationMemoryStore(clock);
        store.recordExchange("user", "hello", "hi");

        clock.advance(Duration.ofMinutes(31));

        assertTrue(store.history("user").isEmpty());
    }

    @Test
    void shouldPersistHistoryAcrossInstances() {
        Instant now = Instant.parse("2026-08-17T00:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        String file = tempDir.resolve("history.json").toString();

        InMemoryConversationMemoryStore first = new InMemoryConversationMemoryStore(clock, file);
        first.recordExchange("user", "你好", "你好呀，有什么可以帮你？");

        InMemoryConversationMemoryStore second = new InMemoryConversationMemoryStore(clock, file);
        List<ChatMessage> history = second.history("user");
        assertEquals(2, history.size());
        assertEquals("你好", history.getFirst().content());
        assertEquals("你好呀，有什么可以帮你？", history.getLast().content());
    }

    @Test
    void shouldEvictOldestSessionWhenOverCapacity() {
        MutableClock clock = new MutableClock();
        InMemoryConversationMemoryStore store = new InMemoryConversationMemoryStore(clock);

        for (int index = 0; index < InMemoryConversationMemoryStore.MAX_SESSIONS + 1; index++) {
            store.recordExchange("user-" + index, "hello", "hi");
            clock.advance(Duration.ofSeconds(1));
        }

        // 最久未交互的会话被淘汰，最近用户仍保留
        assertTrue(store.history("user-0").isEmpty());
        assertEquals(2, store.history("user-" + InMemoryConversationMemoryStore.MAX_SESSIONS).size());
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-08-17T00:00:00Z");

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
