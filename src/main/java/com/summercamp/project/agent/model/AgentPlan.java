package com.summercamp.project.agent.model;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record AgentPlan(List<AgentStep> steps) {

    public AgentPlan {
        steps = List.copyOf(steps);
        if (steps.isEmpty() || steps.size() > 12) {
            throw new IllegalArgumentException("Agent 计划必须包含 1～12 个步骤");
        }
        validateGraph(steps);
    }

    public AgentStep requireStep(String id) {
        return steps.stream()
                .filter(step -> step.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知 Agent 步骤：" + id));
    }

    private static void validateGraph(List<AgentStep> steps) {
        Map<String, AgentStep> byId = new HashMap<>();
        for (AgentStep step : steps) {
            if (byId.putIfAbsent(step.id(), step) != null) {
                throw new IllegalArgumentException("存在重复 Agent 步骤：" + step.id());
            }
        }
        for (AgentStep step : steps) {
            for (String dependency : step.dependsOn()) {
                if (!byId.containsKey(dependency)) {
                    throw new IllegalArgumentException("步骤 " + step.id() + " 存在未知依赖：" + dependency);
                }
            }
        }
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (AgentStep step : steps) {
            detectCycle(step.id(), byId, visiting, visited);
        }
    }

    private static void detectCycle(
            String id,
            Map<String, AgentStep> byId,
            Set<String> visiting,
            Set<String> visited) {
        if (visited.contains(id)) {
            return;
        }
        if (!visiting.add(id)) {
            throw new IllegalArgumentException("Agent 计划存在循环依赖：" + id);
        }
        for (String dependency : byId.get(id).dependsOn()) {
            detectCycle(dependency, byId, visiting, visited);
        }
        visiting.remove(id);
        visited.add(id);
    }
}
