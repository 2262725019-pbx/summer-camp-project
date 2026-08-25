package com.summercamp.project.agent.planning;

import com.summercamp.project.agent.model.AgentPlan;
import com.summercamp.project.agent.model.AgentStep;
import com.summercamp.project.agent.model.AgentStepType;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class TaskPlanner {

    public AgentPlan createHealthPlan() {
        return new AgentPlan(List.of(
                step("parse-goal", AgentStepType.PARSE, "health-goal-parser", List.of(), true),
                step("validate-goal", AgentStepType.VALIDATE, "health-goal-validator", List.of("parse-goal"), true),
                step("retrieve-health-knowledge", AgentStepType.RAG, "keyword-rag", List.of("validate-goal"), false),
                step("query-weather", AgentStepType.WEATHER, "amap-weather", List.of("validate-goal"), false),
                step("calculate-nutrition", AgentStepType.SKILL, "muscle-gain-meal-plan", List.of("validate-goal"), true),
                step("generate-exercise-plan", AgentStepType.SKILL, "exercise-health-advice",
                        List.of("retrieve-health-knowledge", "query-weather"), true),
                step("generate-meal-schedule", AgentStepType.ASSEMBLY, "seven-day-meal-schedule",
                        List.of("calculate-nutrition", "generate-exercise-plan"), true),
                step("assemble-daily-schedule", AgentStepType.ASSEMBLY, "health-plan-assembler",
                        List.of("generate-meal-schedule"), true),
                step("evaluate-plan", AgentStepType.EVALUATION, "health-plan-evaluator",
                        List.of("assemble-daily-schedule"), true),
                step("generate-cover", AgentStepType.IMAGE, "image-generation",
                        List.of("evaluate-plan"), false),
                step("create-result-page", AgentStepType.RESULT_PAGE, "health-result-page",
                        List.of("evaluate-plan", "generate-cover"), false),
                step("generate-result-qr", AgentStepType.QR_CODE, "generate-qr-code",
                        List.of("create-result-page"), false)));
    }

    private AgentStep step(
            String id,
            AgentStepType type,
            String capability,
            List<String> dependsOn,
            boolean required) {
        return new AgentStep(id, type, capability, dependsOn, 2, required);
    }
}
