package com.summercamp.project.agent.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.summercamp.project.agent.HealthPlanAgent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "bot.enabled=false",
            "agent.persistence.enabled=true",
            "agent.persistence.database-path=target/test-state/sqlite-agent-context.db"
        })
class SqliteAgentContextTest {

    @Autowired
    private AgentRunStore agentRunStore;

    @Autowired
    private HealthPlanAgent healthPlanAgent;

    @Test
    void startsApplicationContextWithSqlitePersistenceEnabled() {
        assertThat(agentRunStore).isInstanceOf(SqliteAgentRunStore.class);
        assertThat(healthPlanAgent).isNotNull();
    }
}
