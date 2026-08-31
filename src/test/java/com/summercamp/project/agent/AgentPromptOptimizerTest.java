package com.summercamp.project.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.summercamp.project.config.AgentOptimizationProperties;
import com.summercamp.project.llm.ChatMessage;
import com.summercamp.project.rag.RagContext;
import com.summercamp.project.rag.RagDocument;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentPromptOptimizerTest {

    private final AgentPromptOptimizer optimizer = new AgentPromptOptimizer(
            new AgentOptimizationProperties(true, Duration.ofMinutes(10), Duration.ofMinutes(30),
                    20, 100, 2, 80));

    @Test
    void keepsOnlyRecentHealthRelatedHistory() {
        List<ChatMessage> result = optimizer.relevantHistory(List.of(
                ChatMessage.user("讲个笑话"),
                ChatMessage.assistant("好的"),
                ChatMessage.user("我的训练目标是增肌"),
                ChatMessage.assistant("建议注意饮食和睡眠"),
                ChatMessage.user("今天天气怎么样")));

        assertThat(result).extracting(ChatMessage::content)
                .containsExactly("建议注意饮食和睡眠", "今天天气怎么样");
    }

    @Test
    void truncatesRagContextWithoutDroppingHitMetadata() {
        RagContext input = new RagContext(List.of(new RagContext.Hit(
                new RagDocument("health", "健康", List.of("健康"), "参考"), 3)), "x".repeat(200));

        RagContext result = optimizer.compact(input);

        assertThat(result.hits()).hasSize(1);
        assertThat(result.promptContext()).hasSize(101).endsWith("…");
    }
}
