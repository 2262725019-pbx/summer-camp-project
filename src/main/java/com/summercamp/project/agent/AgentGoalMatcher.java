package com.summercamp.project.agent;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public final class AgentGoalMatcher {
    public static final String EMPTY_GOAL_REPLY =
            "请在 /agent 后告诉我最终目标，例如：/agent 帮我制定未来7天的健康生活规划。";

    private static final Pattern EXPLICIT_AGENT = Pattern.compile(
            "(?is)^/agent(?:\\s+(.*))?$"
    );
    private static final List<String> PLANNING_INTENTS = List.of(
            "制定", "规划", "计划", "安排", "方案"
    );
    private static final List<List<String>> HEALTH_DOMAINS = List.of(
            List.of("运动", "锻炼", "健身", "训练", "跑步"),
            List.of("饮食", "营养", "餐食", "食谱", "增肌饮食"),
            List.of("作息", "睡眠", "早睡", "生活习惯"),
            List.of("天气", "气温", "下雨", "户外")
    );
    private static final List<String> LONG_TERM_SEMANTICS = List.of(
            "未来", "本周", "一周", "7天", "７天", "七天", "每天", "完整", "综合", "健康生活"
    );
    private static final List<String> NEGATED_PLANNING = List.of(
            "不用制定", "不要制定", "不需要规划", "取消计划", "取消规划"
    );

    public Optional<String> match(String text) {
        AgentGoalMatch result = parse(text);
        return result.status() == AgentGoalMatch.Status.MATCHED
                ? Optional.of(result.goal())
                : Optional.empty();
    }

    public AgentGoalMatch parse(String text) {
        String command = text == null ? "" : text.strip();
        if (command.isBlank()) {
            return AgentGoalMatch.notMatched();
        }

        Matcher explicit = EXPLICIT_AGENT.matcher(command);
        if (explicit.matches()) {
            String goal = explicit.group(1);
            return goal == null || goal.isBlank()
                    ? AgentGoalMatch.emptyGoal()
                    : AgentGoalMatch.matched(goal);
        }
        if (command.startsWith("/")) {
            return AgentGoalMatch.notMatched();
        }
        if (containsAny(command, NEGATED_PLANNING)
                || !containsAny(command, PLANNING_INTENTS)
                || !containsAny(command, LONG_TERM_SEMANTICS)) {
            return AgentGoalMatch.notMatched();
        }

        long matchedDomains = HEALTH_DOMAINS.stream()
                .filter(domain -> containsAny(command, domain))
                .count();
        return matchedDomains >= 2
                ? AgentGoalMatch.matched(command)
                : AgentGoalMatch.notMatched();
    }

    private boolean containsAny(String text, List<String> keywords) {
        return keywords.stream().anyMatch(text::contains);
    }
}
