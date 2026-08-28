package com.summercamp.project.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SessionFactMemoryTest {

    @Test
    void shouldExtractExactProjectFactsWithTypedKeysAndSources() {
        InMemoryConversationMemoryStore store = new InMemoryConversationMemoryStore();

        MemoryContext context = store.recall("user", """
                演示地点：镇江
                答辩重点: 断点续跑
                后端语言： Java
                """);
        Map<SessionFactKey, SessionFact> facts = facts(context);

        assertEquals("镇江", facts.get(SessionFactKey.LOCATION).value());
        assertEquals("断点续跑", facts.get(SessionFactKey.DEMO_FOCUS).value());
        assertEquals("Java", facts.get(SessionFactKey.PREFERRED_BACKEND_LANGUAGE).value());
        assertEquals(SessionFactSourceType.EXPLICIT_FIELD,
                facts.get(SessionFactKey.LOCATION).sourceType());
        assertNotNull(facts.get(SessionFactKey.LOCATION).updatedAt());
        assertFalse(facts.get(SessionFactKey.LOCATION).sourceEntryId().isBlank());
    }

    @Test
    void shouldExtractOnlyAllowlistedHealthPlanningParameters() {
        InMemoryConversationMemoryStore store = new InMemoryConversationMemoryStore();

        MemoryContext context = store.recall("user", """
                运动目标：增肌
                运动偏好：快走和自重训练
                每周训练：4次
                每次训练：60分钟
                每日餐数：4餐
                疾病诊断：高血压
                药物：示例药物
                """);
        Map<SessionFactKey, SessionFact> facts = facts(context);

        assertEquals(5, facts.size());
        assertEquals("增肌", facts.get(SessionFactKey.EXERCISE_GOAL).value());
        assertEquals("快走和自重训练", facts.get(SessionFactKey.EXERCISE_PREFERENCE).value());
        assertEquals("4", facts.get(SessionFactKey.TRAINING_FREQUENCY_PER_WEEK).value());
        assertEquals("60", facts.get(SessionFactKey.TRAINING_DURATION_MINUTES).value());
        assertEquals("4", facts.get(SessionFactKey.DAILY_MEAL_COUNT).value());
        assertTrue(context.factPromptContext().contains("每周训练次数=4次/周"));
    }

    @Test
    void shouldUpdateOneFactAndPreserveAllOtherFacts() {
        InMemoryConversationMemoryStore store = new InMemoryConversationMemoryStore();
        store.recall("user", """
                演示地点：镇江
                答辩重点：断点续跑
                后端语言：Java
                """);

        MemoryContext context = store.recall("user", "地点改成南京，其他保持不变。");
        Map<SessionFactKey, SessionFact> facts = facts(context);

        assertEquals(3, facts.size());
        assertEquals("南京", facts.get(SessionFactKey.LOCATION).value());
        assertEquals(SessionFactSourceType.EXPLICIT_UPDATE,
                facts.get(SessionFactKey.LOCATION).sourceType());
        assertEquals("断点续跑", facts.get(SessionFactKey.DEMO_FOCUS).value());
        assertEquals("Java", facts.get(SessionFactKey.PREFERRED_BACKEND_LANGUAGE).value());
        assertEquals(1, context.diagnostics().memoryFactsExtracted());
        assertEquals(1, context.diagnostics().memoryFactsUpdated());
    }

    @Test
    void shouldRemoveOnlyAnExplicitlyNamedFact() {
        InMemoryConversationMemoryStore store = new InMemoryConversationMemoryStore();
        store.recall("user", "演示地点：镇江\n答辩重点：断点续跑");

        MemoryContext context = store.recall("user", "清除演示地点");

        assertFalse(facts(context).containsKey(SessionFactKey.LOCATION));
        assertEquals("断点续跑", facts(context).get(SessionFactKey.DEMO_FOCUS).value());
        assertEquals(1, context.diagnostics().memoryFactsRemoved());
    }

    @Test
    void shouldNotTreatAmbiguousForgettingAsRemoval() {
        InMemoryConversationMemoryStore store = new InMemoryConversationMemoryStore();
        store.recall("user", "演示地点：镇江");

        MemoryContext context = store.recall("user", "我忘了演示地点");

        assertEquals("镇江", facts(context).get(SessionFactKey.LOCATION).value());
        assertEquals(0, context.diagnostics().memoryFactsRemoved());
    }

    @Test
    void shouldMakeCurrentFactAuthoritativeOverAnOldRecalledEpisode() {
        InMemoryConversationMemoryStore store = new InMemoryConversationMemoryStore();
        store.recall("user", "演示地点：镇江");
        store.recordExchange("user", "演示地点：镇江，地点安排已经确认。", "当前地点是镇江。");
        addFillers(store, "user");

        MemoryContext context = store.recall(
                "user", "这次演示地点改成南京，地点安排仍需参考旧记录。");

        assertEquals("南京", facts(context).get(SessionFactKey.LOCATION).value());
        assertFalse(context.recalledEntries().isEmpty());
        assertTrue(context.recalledEntries().getFirst().entry().userText().contains("镇江"));
        assertTrue(context.promptContext().contains("[SESSION_FACTS]"));
        assertTrue(context.promptContext().indexOf("[历史对话]")
                < context.promptContext().indexOf("[SESSION_FACTS]"));
        assertTrue(context.promptContext().contains("当前消息 > SESSION_FACTS"));
        assertTrue(context.factPromptContext().contains("演示地点=南京"));
        assertFalse(context.factPromptContext().contains("镇江"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "我昨天去了南京",
        "我朋友喜欢Java",
        "老师说断点续跑不错",
        "可能每周训练4次比较好"
    })
    void shouldNotExtractFactsFromImplicitOrSpeculativeText(String message) {
        InMemoryConversationMemoryStore store = new InMemoryConversationMemoryStore();

        assertTrue(store.recall("user", message).sessionFacts().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "API Key：abc",
        "password：123",
        "authorization：Bearer abc",
        "secret：hidden",
        "access token：xyz",
        "token：xyz",
        "后端语言：API Key sk-abcdef123456"
    })
    void shouldNeverStoreCredentialLikeFieldsOrValues(String message) {
        InMemoryConversationMemoryStore store = new InMemoryConversationMemoryStore();

        MemoryContext context = store.recall("user", message);

        assertTrue(context.sessionFacts().isEmpty());
        assertFalse(context.factPromptContext().contains("abc"));
        assertFalse(context.factPromptContext().contains("123"));
        assertFalse(context.factPromptContext().contains("xyz"));
    }

    @Test
    void shouldKeepFactsStrictlyScopedToOneUser() {
        InMemoryConversationMemoryStore store = new InMemoryConversationMemoryStore();
        store.recall("user-a", "演示地点：南京");

        assertEquals("南京", facts(store.recall("user-a", "普通问题"))
                .get(SessionFactKey.LOCATION).value());
        assertTrue(store.recall("user-b", "普通问题").sessionFacts().isEmpty());
    }

    @Test
    void shouldExpireAndClearFactsTogetherWithConversationMemory() {
        MutableClock clock = new MutableClock();
        InMemoryConversationMemoryStore store = new InMemoryConversationMemoryStore(clock);
        store.recall("expired", "演示地点：南京");
        store.recordExchange("expired", "演示地点：南京", "记住了");
        clock.advance(Duration.ofMinutes(31));

        assertTrue(store.recall("expired", "普通问题").sessionFacts().isEmpty());
        assertTrue(store.history("expired").isEmpty());

        store.recall("cleared", "答辩重点：RAG检索");
        store.recordExchange("cleared", "答辩重点：RAG检索", "记住了");
        store.clear("cleared");

        assertTrue(store.recall("cleared", "普通问题").sessionFacts().isEmpty());
        assertTrue(store.history("cleared").isEmpty());
        assertEquals(0, store.episodicSize("cleared"));
    }

    @Test
    void shouldKeepFactPromptWithinHardBudgetAndPreferRecentUpdates() {
        InMemoryConversationMemoryStore store = new InMemoryConversationMemoryStore();
        String longValue = "甲".repeat(SessionFactExtractor.MAX_FACT_VALUE_CHARS + 50);
        store.recall("user", """
                演示地点：%s
                答辩重点：%s
                演示顺序：%s
                后端语言：%s
                运动目标：%s
                运动偏好：%s
                每周训练：4次
                每次训练：60分钟
                每日餐数：4餐
                """.formatted(longValue, longValue, longValue, longValue, longValue, longValue));

        MemoryContext context = store.recall("user", "答辩重点：最终校验");

        assertTrue(context.factPromptContext().length()
                <= InMemoryConversationMemoryStore.MAX_FACT_CONTEXT_CHARS);
        assertTrue(context.factPromptContext().contains("答辩重点=最终校验"));
    }

    @Test
    void shouldReportPrivacySafeFactMetrics() {
        InMemoryConversationMemoryStore store = new InMemoryConversationMemoryStore();

        MemoryContext created = store.recall("user", "演示地点：镇江\n答辩重点：断点续跑");
        MemoryContext changed = store.recall("user", "地点改成南京");

        assertEquals(2, created.diagnostics().memorySessionFacts());
        assertEquals(2, created.diagnostics().memoryFactsExtracted());
        assertEquals(2, created.diagnostics().memoryFactsUpdated());
        assertEquals(0, created.diagnostics().memoryFactsRemoved());
        assertEquals(2, changed.diagnostics().memorySessionFacts());
        assertEquals(1, changed.diagnostics().memoryFactsUpdated());
    }

    private Map<SessionFactKey, SessionFact> facts(MemoryContext context) {
        return context.sessionFacts().stream()
                .collect(Collectors.toMap(SessionFact::key, Function.identity()));
    }

    private void addFillers(InMemoryConversationMemoryStore store, String userId) {
        for (int index = 0; index < 6; index++) {
            store.recordExchange(userId, "日常闲聊" + index, "普通回复" + index);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-08-28T00:00:00Z");

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
