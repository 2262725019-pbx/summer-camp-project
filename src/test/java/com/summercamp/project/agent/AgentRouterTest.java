package com.summercamp.project.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.summercamp.project.config.HealthAgentProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class AgentRouterTest {

    @Test
    void routesComplexHealthPlanningGoals() {
        AgentRouter router = new AgentRouter(new HealthAgentProperties(true, Duration.ofMinutes(30), false));

        assertThat(router.supports("帮我制定未来 7 天的增肌健康生活完整方案")).isTrue();
    }

    @Test
    void leavesSimpleMealQuestionsToExistingSkillsOrChat() {
        AgentRouter router = new AgentRouter(new HealthAgentProperties(true, Duration.ofMinutes(30), false));

        assertThat(router.supports("帮我生成一个增肌饮食计划")).isFalse();
    }

    @Test
    void routesNaturalFourteenDayRoutinePlans() {
        AgentRouter router = new AgentRouter(new HealthAgentProperties(true, Duration.ofMinutes(30), false));

        assertThat(router.supports("帮我做一份未来十四日早睡早起方案")).isTrue();
        assertThat(router.supports("帮我做未来12天提高身体素质的规划")).isTrue();
    }

    @Test
    void canBeDisabledByConfiguration() {
        AgentRouter router = new AgentRouter(new HealthAgentProperties(false, Duration.ofMinutes(30), false));

        assertThat(router.supports("帮我制定未来 7 天的增肌健康生活完整方案")).isFalse();
    }
}
