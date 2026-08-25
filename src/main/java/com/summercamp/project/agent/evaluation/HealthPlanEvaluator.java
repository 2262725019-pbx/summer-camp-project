package com.summercamp.project.agent.evaluation;

import com.summercamp.project.agent.artifact.HealthPlanArtifact;
import com.summercamp.project.agent.model.HealthGoal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class HealthPlanEvaluator {

    public EvaluationReport evaluate(HealthGoal goal, HealthPlanArtifact artifact) {
        List<String> issues = new ArrayList<>();
        String content = artifact.content();
        for (int day = 1; day <= goal.days(); day++) {
            if (!content.contains("第" + day + "天")) {
                issues.add("缺少第 " + day + " 天安排");
            }
        }
        requireSection(content, "天气与训练环境", issues);
        requireSection(content, "训练建议", issues);
        requireSection(content, "饮食与营养建议", issues);
        requireSection(content, "一周购物清单", issues);
        requireSection(content, "恢复与安全", issues);
        requireSection(content, "参考依据", issues);
        if (!content.contains("不替代医生")) {
            issues.add("缺少健康免责声明");
        }
        return new EvaluationReport(issues.isEmpty(), issues);
    }

    private void requireSection(String content, String name, List<String> issues) {
        if (!content.contains(name)) {
            issues.add("缺少“" + name + "”部分");
        }
    }
}
