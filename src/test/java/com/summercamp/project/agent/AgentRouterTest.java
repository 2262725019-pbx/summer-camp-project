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
    void canBeDisabledByConfiguration() {
        AgentRouter router = new AgentRouter(new HealthAgentProperties(false, Duration.ofMinutes(30), false));

        assertThat(router.supports("帮我制定未来 7 天的增肌健康生活完整方案")).isFalse();
    }
}
