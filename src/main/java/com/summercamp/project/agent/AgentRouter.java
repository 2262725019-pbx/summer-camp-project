package com.summercamp.project.agent;

import com.summercamp.project.config.HealthAgentProperties;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class AgentRouter {

    private static final List<String> GOAL_TERMS = List.of(
            "增肌", "长肌肉", "健身增重", "减脂", "减肥", "控制体重", "体能", "耐力",
            "运动能力", "提高身体素质", "规律作息", "健康生活", "生活习惯", "早睡早起");
    private static final List<String> PLAN_TERMS = List.of(
            "计划", "方案", "规划");
    private static final List<String> COMPLEXITY_TERMS = List.of(
            "未来7天", "未来七天", "一周", "七天", "结果页面", "二维码", "完整");
    private static final Pattern MULTI_DAY = Pattern.compile(
            "(?:未来|接下来)?(?:[3-9]|1[0-4]|三|四|五|六|七|八|九|十|十一|十二|十三|十四)(?:天|日)");

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
        boolean complex = COMPLEXITY_TERMS.stream().anyMatch(normalized::contains)
                || MULTI_DAY.matcher(normalized).find();
        return goal && plan && complex;
    }

    private String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{S}\\s]+", "");
    }
}
