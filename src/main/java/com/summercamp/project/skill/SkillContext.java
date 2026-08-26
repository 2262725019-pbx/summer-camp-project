package com.summercamp.project.skill;

import com.summercamp.project.agent.AgentRunMetrics;
import com.summercamp.project.llm.ChatMessage;
import java.util.List;

public record SkillContext(
        String userId,
        String text,
        List<ChatMessage> history,
        boolean voiceMessage,
        AgentRunMetrics metrics,
        SkillTrustedContext trustedContext,
        SkillExecutionMode executionMode) {

    public SkillContext {
        userId = userId == null ? "" : userId;
        text = text == null ? "" : text;
        history = List.copyOf(history);
        metrics = metrics == null ? AgentRunMetrics.unobserved() : metrics;
        trustedContext = trustedContext == null ? SkillTrustedContext.empty() : trustedContext;
        executionMode = executionMode == null ? SkillExecutionMode.STANDARD : executionMode;
    }

    public SkillContext(
            String userId,
            String text,
            List<ChatMessage> history,
            boolean voiceMessage,
            AgentRunMetrics metrics,
            SkillTrustedContext trustedContext
    ) {
        this(
                userId,
                text,
                history,
                voiceMessage,
                metrics,
                trustedContext,
                SkillExecutionMode.STANDARD);
    }

    public SkillContext(
            String userId,
            String text,
            List<ChatMessage> history,
            boolean voiceMessage,
            AgentRunMetrics metrics
    ) {
        this(
                userId,
                text,
                history,
                voiceMessage,
                metrics,
                SkillTrustedContext.empty(),
                SkillExecutionMode.STANDARD);
    }

    public SkillContext(
            String userId,
            String text,
            List<ChatMessage> history,
            boolean voiceMessage
    ) {
        this(
                userId,
                text,
                history,
                voiceMessage,
                AgentRunMetrics.unobserved(),
                SkillTrustedContext.empty(),
                SkillExecutionMode.STANDARD);
    }
}
