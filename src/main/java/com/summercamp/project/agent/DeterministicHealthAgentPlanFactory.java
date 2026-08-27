package com.summercamp.project.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Last-resort plan factory for explicit, currently supported health-planning goals. */
public final class DeterministicHealthAgentPlanFactory {
    private static final Pattern LABELED_LOCATION = Pattern.compile(
            "(?:所在地|所在城市|城市|地区)\\s*[：:=]\\s*([^\\r\\n，,。；;]{1,20})");
    private static final Pattern CONTEXTUAL_CITY = Pattern.compile(
            "(?:结合|根据|参考|查询|查看)\\s*([\\p{IsHan}]{2,8}市)");
    private static final List<GoalRequirement> ORDER = List.of(
            GoalRequirement.TEMPORAL,
            GoalRequirement.WEATHER,
            GoalRequirement.EXERCISE,
            GoalRequirement.MEAL);

    private final GoalRequirementExtractor requirementExtractor = new GoalRequirementExtractor();

    public Optional<AgentPlan> create(String goal) {
        Set<GoalRequirement> requirements = requirementExtractor.extract(goal);
        List<GoalRequirement> actionable = ORDER.stream().filter(requirements::contains).toList();
        boolean healthScenario = requirements.contains(GoalRequirement.EXERCISE)
                || requirements.contains(GoalRequirement.MEAL)
                || requirements.contains(GoalRequirement.LIFESTYLE);
        if (!healthScenario || actionable.size() < AgentPlanValidator.MIN_DISTINCT_BUSINESS_TASKS) {
            return Optional.empty();
        }
        String location = requirements.contains(GoalRequirement.WEATHER)
                ? location(goal).orElse(null)
                : null;
        if (requirements.contains(GoalRequirement.WEATHER) && location == null) {
            return Optional.empty();
        }

        List<AgentStep> steps = new ArrayList<>();
        Map<GoalRequirement, String> ids = new LinkedHashMap<>();
        int sequence = 1;
        for (GoalRequirement requirement : actionable) {
            String id = "D" + sequence++;
            ids.put(requirement, id);
            List<String> dependencies = dependencies(requirement, ids);
            steps.add(step(id, requirement, location, dependencies));
        }
        List<String> businessIds = steps.stream().map(AgentStep::id).toList();
        String validationId = "D" + sequence++;
        steps.add(new AgentStep(
                validationId,
                AgentAction.VALIDATE,
                "校验已完成健康规划能力",
                "确保所有业务分支成功后才能汇总",
                businessIds,
                Map.of()));
        steps.add(new AgentStep(
                "D" + sequence,
                AgentAction.SYNTHESIZE,
                "汇总可执行健康生活方案",
                "生成通过最终一致性门禁的用户方案",
                List.of(validationId),
                Map.of()));
        return Optional.of(new AgentPlan(goal, steps));
    }

    private List<String> dependencies(
            GoalRequirement requirement,
            Map<GoalRequirement, String> existing
    ) {
        if (requirement == GoalRequirement.WEATHER && existing.containsKey(GoalRequirement.TEMPORAL)) {
            return List.of(existing.get(GoalRequirement.TEMPORAL));
        }
        if (requirement == GoalRequirement.EXERCISE) {
            if (existing.containsKey(GoalRequirement.WEATHER)) {
                return List.of(existing.get(GoalRequirement.WEATHER));
            }
            if (existing.containsKey(GoalRequirement.TEMPORAL)) {
                return List.of(existing.get(GoalRequirement.TEMPORAL));
            }
        }
        if (requirement == GoalRequirement.MEAL
                && existing.containsKey(GoalRequirement.TEMPORAL)) {
            return List.of(existing.get(GoalRequirement.TEMPORAL));
        }
        return List.of();
    }

    private AgentStep step(
            String id,
            GoalRequirement requirement,
            String location,
            List<String> dependencies
    ) {
        return switch (requirement) {
            case TEMPORAL -> new AgentStep(
                    id, AgentAction.GET_DATETIME, "确定计划日期和星期",
                    "建立用户要求的计划时间范围", dependencies,
                    Map.of("timezone", "Asia/Shanghai"));
            case WEATHER -> new AgentStep(
                    id, AgentAction.GET_WEATHER, "获取近期天气",
                    "只用真实近期天气调整室内外安排", dependencies,
                    Map.of("location", location, "period", "THREE_DAYS"));
            case EXERCISE -> new AgentStep(
                    id, AgentAction.RUN_EXERCISE_SKILL, "生成安全运动执行参考",
                    "满足用户明确运动要求", dependencies,
                    Map.of());
            case MEAL -> new AgentStep(
                    id, AgentAction.RUN_MEAL_SKILL, "生成饮食执行参考",
                    "满足用户明确饮食要求", dependencies,
                    Map.of());
            case LIFESTYLE -> throw new IllegalArgumentException("LIFESTYLE has no dedicated action");
        };
    }

    private Optional<String> location(String goal) {
        for (Pattern pattern : List.of(LABELED_LOCATION, CONTEXTUAL_CITY)) {
            Matcher matcher = pattern.matcher(goal == null ? "" : goal);
            if (matcher.find()) {
                String value = matcher.group(1).strip();
                if (!value.isBlank() && value.length() <= 20) {
                    return Optional.of(value);
                }
            }
        }
        return Optional.empty();
    }

}
