package com.summercamp.project.agent;

import com.summercamp.project.config.AgentOptimizationProperties;
import com.summercamp.project.llm.ChatMessage;
import com.summercamp.project.rag.RagContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class AgentPromptOptimizer {

    private static final List<String> HEALTH_TERMS = List.of(
            "健康", "增肌", "减脂", "减肥", "体能", "训练", "运动", "饮食", "营养",
            "热量", "蛋白质", "睡眠", "作息", "身高", "体重", "过敏", "天气");

    private final AgentOptimizationProperties properties;

    public AgentPromptOptimizer(AgentOptimizationProperties properties) {
        this.properties = properties;
    }

    public RagContext compact(RagContext context) {
        if (context == null || !context.matched()) {
            return RagContext.empty();
        }
        String prompt = limit(context.promptContext(), properties.maxRagPromptChars());
        return new RagContext(context.hits(), prompt);
    }

    public String compactWeather(String weather) {
        return limit(weather, 800);
    }

    public List<ChatMessage> relevantHistory(List<ChatMessage> history) {
        if (history == null || history.isEmpty() || properties.maxHistoryMessages() == 0) {
            return List.of();
        }
        List<ChatMessage> relevant = history.stream()
                .filter(message -> isHealthRelated(message.content()))
                .toList();
        int start = Math.max(0, relevant.size() - properties.maxHistoryMessages());
        List<ChatMessage> selected = new ArrayList<>();
        int characters = 0;
        for (int index = relevant.size() - 1; index >= start; index--) {
            ChatMessage message = relevant.get(index);
            int length = message.content() == null ? 0 : message.content().length();
            if (characters + length > properties.maxHistoryChars()) {
                continue;
            }
            selected.addFirst(message);
            characters += length;
        }
        return List.copyOf(selected);
    }

    private boolean isHealthRelated(String text) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
        return HEALTH_TERMS.stream().anyMatch(normalized::contains);
    }

    private String limit(String value, int maximum) {
        String normalized = value == null ? "" : value.strip();
        return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum) + "…";
    }
}
