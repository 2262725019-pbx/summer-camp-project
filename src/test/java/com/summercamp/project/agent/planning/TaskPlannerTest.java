package com.summercamp.project.agent.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.summercamp.project.agent.model.AgentPlan;
import com.summercamp.project.agent.model.AgentStep;
import com.summercamp.project.agent.model.AgentStepType;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskPlannerTest {

    @Test
    void createsTheControlledTwelveStepPlan() {
        AgentPlan plan = new TaskPlanner().createHealthPlan();

        assertThat(plan.steps()).hasSize(12);
        assertThat(plan.requireStep("generate-exercise-plan").dependsOn())
                .containsExactly("retrieve-health-knowledge", "query-weather");
        assertThat(plan.requireStep("generate-result-qr").dependsOn())
                .containsExactly("create-result-page");
    }

    @Test
    void rejectsCycles() {
        AgentStep first = new AgentStep("a", AgentStepType.PARSE, "a", List.of("b"), 1, true);
        AgentStep second = new AgentStep("b", AgentStepType.VALIDATE, "b", List.of("a"), 1, true);

        assertThatThrownBy(() -> new AgentPlan(List.of(first, second)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("循环依赖");
    }
}
