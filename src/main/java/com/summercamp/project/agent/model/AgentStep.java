package com.summercamp.project.agent.model;

import java.util.List;

public record AgentStep(
        String id,
        AgentStepType type,
        String capability,
        List<String> dependsOn,
        int maxAttempts,
        boolean required) {

    public AgentStep {
        id = requireText(id, "步骤 ID");
        capability = requireText(capability, "能力名称");
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        if (type == null) {
            throw new IllegalArgumentException("步骤类型不能为空");
        }
        if (maxAttempts < 1 || maxAttempts > 2) {
            throw new IllegalArgumentException("步骤重试次数必须为 1 或 2");
        }
    }

    private static String requireText(String value, String name) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
        return normalized;
    }
}
