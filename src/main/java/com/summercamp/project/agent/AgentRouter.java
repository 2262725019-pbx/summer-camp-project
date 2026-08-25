package com.summercamp.project.agent;

import com.summercamp.project.config.HealthAgentProperties;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class AgentRouter {

    private static final List<String> GOAL_TERMS = List.of(
            "增肌", "减脂", "减肥", "体能", "规律作息", "健康生活");
    private static final List<String> PLAN_TERMS = List.of(
            "完整计划", "完整方案", "生活方案", "健康计划", "健康生活方案", "七日计划", "一周计划");
    private static final List<String> COMPLEXITY_TERMS = List.of(
            "未来7天", "未来七天", "一周", "七天", "结果页面", "二维码", "完整");

    private final HealthAgentProperties properties;

    public AgentRouter(HealthAgentProperties properties) {
        this.properties = properties;
        properties.validate();
    }

    public boolean supports(String text) {
        if (!properties.enabled()) {
            return false;
        }
        String normalized = normalize(text);
        boolean goal = GOAL_TERMS.stream().anyMatch(normalized::contains);
        boolean plan = PLAN_TERMS.stream().anyMatch(normalized::contains);
        boolean complex = COMPLEXITY_TERMS.stream().anyMatch(normalized::contains);
        return goal && plan && complex;
    }

    private String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{S}\\s]+", "");
    }
}
