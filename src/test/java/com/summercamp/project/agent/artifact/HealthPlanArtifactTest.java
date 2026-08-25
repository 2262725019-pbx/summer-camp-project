package com.summercamp.project.agent.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import com.summercamp.project.agent.evaluation.HealthPlanEvaluator;
import com.summercamp.project.agent.model.HealthGoal;
import com.summercamp.project.agent.model.HealthGoalType;
import com.summercamp.project.rag.RagContext;
import com.summercamp.project.rag.RagDocument;
import java.util.List;
import org.junit.jupiter.api.Test;

class HealthPlanArtifactTest {

    @Test
    void assemblesAndValidatesEveryDayOfThePlan() {
        HealthGoal goal = new HealthGoal(
                HealthGoalType.MUSCLE_GAIN, 7, "男", 20, 175.0, 70.0, "上海",
                4, 60, 4, "中度", true, true, List.of(), "goal");
        RagContext rag = new RagContext(List.of(new RagContext.Hit(
                new RagDocument("healthy", "健康生活", List.of("健康"), "参考"), 4)), "参考");

        HealthPlanArtifact artifact = new HealthPlanAssembler().assemble(
                goal, "上海未来三天天气", "营养计划", "训练计划", rag, List.of());

        assertThat(artifact.content()).contains("第1天", "第7天", "购物清单", "不替代医生");
        assertThat(new HealthPlanEvaluator().evaluate(goal, artifact).valid()).isTrue();
    }
}
