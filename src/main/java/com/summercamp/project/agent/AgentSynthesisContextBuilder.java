package com.summercamp.project.agent;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public final class AgentSynthesisContextBuilder {
    public static final int MAX_OBSERVATION_CHARS = 4_000;
    public static final int MAX_TOTAL_CHARS = 20_000;

    private static final Set<AgentAction> INCLUDED_ACTIONS = EnumSet.of(
            AgentAction.GET_DATETIME,
            AgentAction.GET_WEATHER,
            AgentAction.RETRIEVE_KNOWLEDGE,
            AgentAction.RUN_EXERCISE_SKILL,
            AgentAction.RUN_MEAL_SKILL,
            AgentAction.CALCULATE,
            AgentAction.CREATE_TODO,
            AgentAction.VALIDATE
    );
    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "(?i).*(api.?key|authorization|password|secret|access.?token|private.?key).*"
    );
    private static final Pattern BINARY_KEY = Pattern.compile(
            "(?i).*(binary|base64|bytes|image|audio|video).*"
    );
    private static final Pattern INTERNAL_KEY = Pattern.compile(
            "(?i)(code|recoverable|status|stack.?trace|debug|exception)"
    );
    private static final Pattern INLINE_SECRET = Pattern.compile(
            "(?i)(api.?key|authorization|password|secret|access.?token)\\s*[:=]\\s*[^\\s,;]+"
    );

    public String build(String originalGoal, AgentPlan plan, AgentStateView state) {
        if (originalGoal == null || originalGoal.isBlank()) {
            throw new IllegalArgumentException("originalGoal must not be blank");
        }
        if (plan == null || state == null) {
            throw new IllegalArgumentException("plan and state must not be null");
        }

        StringBuilder result = new StringBuilder(MAX_TOTAL_CHARS);
        appendWithinTotal(result, "用户目标：" + sanitize(originalGoal) + "\n\n已验证的真实执行结果：\n");
        for (AgentStep step : plan.steps()) {
            if (!INCLUDED_ACTIONS.contains(step.action())
                    || state.statusOf(step.id()) != AgentStepStatus.COMPLETED) {
                continue;
            }
            AgentObservation observation = state.findObservation(step.id()).orElse(null);
            if (observation == null || !observation.success()) {
                continue;
            }
            String block = observationBlock(step.action(), observation);
            appendWithinTotal(result, safeTruncate(block, MAX_OBSERVATION_CHARS));
            if (result.length() >= MAX_TOTAL_CHARS) {
                break;
            }
        }
        return result.toString();
    }

    private String observationBlock(AgentAction action, AgentObservation observation) {
        StringBuilder block = new StringBuilder();
        block.append("- ").append(label(action)).append("：")
                .append(sanitize(observation.summary())).append('\n');
        observation.structuredData().entrySet().stream()
                .filter(entry -> isSafeKey(entry.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    String value = sanitize(entry.getValue());
                    if (!value.isBlank()) {
                        block.append("  ").append(entry.getKey()).append("：")
                                .append(value).append('\n');
                    }
                });
        return block.toString();
    }

    private boolean isSafeKey(String key) {
        return key != null
                && !SENSITIVE_KEY.matcher(key).matches()
                && !BINARY_KEY.matcher(key).matches()
                && !INTERNAL_KEY.matcher(key).matches();
    }

    private String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        StringBuilder clean = new StringBuilder();
        for (String line : raw.replace('\u0000', ' ').lines().toList()) {
            String stripped = line.strip();
            if (stripped.startsWith("at ")
                    || stripped.startsWith("Caused by:")
                    || stripped.startsWith("Suppressed:")) {
                continue;
            }
            if (!clean.isEmpty()) {
                clean.append(' ');
            }
            clean.append(stripped);
        }
        String redacted = INLINE_SECRET.matcher(clean).replaceAll("$1=[REDACTED]");
        if (redacted.startsWith("data:") || redacted.contains(";base64,")) {
            return "[已省略二进制内容]";
        }
        return redacted;
    }

    private String label(AgentAction action) {
        return switch (action) {
            case GET_DATETIME -> "日期时间";
            case GET_WEATHER -> "天气";
            case RETRIEVE_KNOWLEDGE -> "本地知识检索";
            case RUN_EXERCISE_SKILL -> "运动建议";
            case RUN_MEAL_SKILL -> "饮食建议";
            case CALCULATE -> "计算结果";
            case CREATE_TODO -> "待办结果";
            case VALIDATE -> "一致性校验";
            case SYNTHESIZE -> "最终汇总";
        };
    }

    private void appendWithinTotal(StringBuilder target, String text) {
        int remaining = MAX_TOTAL_CHARS - target.length();
        if (remaining > 0) {
            target.append(safeTruncate(text, remaining));
        }
    }

    private String safeTruncate(String value, int maximum) {
        if (value.length() <= maximum) {
            return value;
        }
        int end = maximum;
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }
}
