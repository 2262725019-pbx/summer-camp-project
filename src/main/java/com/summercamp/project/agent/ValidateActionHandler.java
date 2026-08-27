package com.summercamp.project.agent;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public final class ValidateActionHandler implements AgentActionHandler {
    public static final String VALIDATION_PASSED = "VALIDATION_PASSED";
    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    public static final String NEEDS_USER_INPUT = "NEEDS_USER_INPUT";
    public static final String UPSTREAM_STEP_FAILED = "UPSTREAM_STEP_FAILED";
    public static final String INCOMPLETE_EXECUTION = "INCOMPLETE_EXECUTION";
    public static final String MISSING_REQUIRED_EXERCISE_RESULT =
            "MISSING_REQUIRED_EXERCISE_RESULT";
    public static final String MISSING_REQUIRED_MEAL_RESULT =
            "MISSING_REQUIRED_MEAL_RESULT";
    public static final String MISSING_REQUIRED_WEATHER_RESULT =
            "MISSING_REQUIRED_WEATHER_RESULT";
    public static final String MISSING_REQUIRED_DATETIME_RESULT =
            "MISSING_REQUIRED_DATETIME_RESULT";

    private static final Set<AgentAction> BUSINESS_ACTIONS = EnumSet.of(
            AgentAction.GET_DATETIME,
            AgentAction.GET_WEATHER,
            AgentAction.RETRIEVE_KNOWLEDGE,
            AgentAction.RUN_EXERCISE_SKILL,
            AgentAction.RUN_MEAL_SKILL,
            AgentAction.CALCULATE,
            AgentAction.CREATE_TODO
    );

    private final AgentPlanValidator planValidator = new AgentPlanValidator();
    private final GoalRequirementExtractor requirementExtractor = new GoalRequirementExtractor();

    @Override
    public AgentAction action() {
        return AgentAction.VALIDATE;
    }

    @Override
    public AgentObservation execute(AgentStep step, AgentExecutionContext context) {
        if (step.action() != AgentAction.VALIDATE) {
            return failure(step, VALIDATION_FAILED, "运行时校验步骤类型不匹配", false);
        }
        AgentPlanValidationResult planValidation = planValidator.validate(context.plan());
        if (!planValidation.valid()) {
            return failure(step, VALIDATION_FAILED, "执行计划不满足完整闭环约束", false);
        }

        AgentStateView state = context.state();
        AgentObservation coverageFailure = validateRequiredResults(context, step);
        if (coverageFailure != null) {
            return coverageFailure;
        }
        for (String dependencyId : step.dependsOn()) {
            AgentStepStatus dependencyStatus = state.statusOf(dependencyId);
            Optional<AgentObservation> dependencyObservation = state.findObservation(dependencyId);
            if (dependencyObservation.map(this::needsUserInput).orElse(false)) {
                return failure(
                        step,
                        NEEDS_USER_INPUT,
                        safeUserInputSummary(dependencyObservation.orElseThrow()),
                        true
                );
            }
            if (dependencyStatus == AgentStepStatus.FAILED
                    || dependencyStatus == AgentStepStatus.SKIPPED) {
                return failure(step, UPSTREAM_STEP_FAILED, "必要的上游能力执行失败，无法生成可靠计划", false);
            }
            if (dependencyStatus != AgentStepStatus.COMPLETED) {
                return failure(step, INCOMPLETE_EXECUTION, "校验所需的上游步骤尚未全部完成", false);
            }
        }

        for (AgentStep businessStep : context.plan().steps()) {
            if (!BUSINESS_ACTIONS.contains(businessStep.action())) {
                continue;
            }
            AgentStepStatus status = state.statusOf(businessStep.id());
            Optional<AgentObservation> observation = state.findObservation(businessStep.id());
            if (observation.map(this::needsUserInput).orElse(false)) {
                return failure(step, NEEDS_USER_INPUT, safeUserInputSummary(observation.orElseThrow()), true);
            }
            if (status == AgentStepStatus.FAILED || status == AgentStepStatus.SKIPPED) {
                return failure(step, UPSTREAM_STEP_FAILED, "必要的上游能力执行失败，无法生成可靠计划", false);
            }
            if (status != AgentStepStatus.COMPLETED) {
                return failure(step, INCOMPLETE_EXECUTION, "业务步骤尚未全部执行完成", false);
            }
            if (observation.isEmpty() || !observation.orElseThrow().success()) {
                return failure(step, VALIDATION_FAILED, "步骤状态与执行结果不一致", false);
            }
        }

        return new AgentObservation(
                step.id(),
                true,
                "计划所需信息已完成一致性校验",
                Map.of("code", VALIDATION_PASSED)
        );
    }

    private AgentObservation validateRequiredResults(
            AgentExecutionContext context,
            AgentStep validationStep
    ) {
        Set<GoalRequirement> requirements = requirementExtractor.extract(context.originalGoal());
        for (GoalRequirement requirement : List.of(
                GoalRequirement.TEMPORAL,
                GoalRequirement.EXERCISE,
                GoalRequirement.MEAL,
                GoalRequirement.WEATHER)) {
            if (!requirements.contains(requirement)) {
                continue;
            }
            List<AgentStep> capabilitySteps = context.plan().steps().stream()
                    .filter(candidate -> candidate.action() == requirement.requiredAction())
                    .toList();
            Optional<AgentObservation> waiting = capabilitySteps.stream()
                    .map(candidate -> context.state().findObservation(candidate.id()).orElse(null))
                    .filter(java.util.Objects::nonNull)
                    .filter(this::needsUserInput)
                    .findFirst();
            if (waiting.isPresent()) {
                return failure(
                        validationStep,
                        NEEDS_USER_INPUT,
                        safeUserInputSummary(waiting.orElseThrow()),
                        true
                );
            }
            boolean completed = capabilitySteps.stream().anyMatch(candidate ->
                    context.state().statusOf(candidate.id()) == AgentStepStatus.COMPLETED
                            && context.state().findObservation(candidate.id())
                            .map(AgentObservation::success)
                            .orElse(false));
            if (!completed) {
                return failure(
                        validationStep,
                        missingResultCode(requirement),
                        "原始目标要求的关键能力没有成功执行结果",
                        false
                );
            }
        }
        return null;
    }

    private String missingResultCode(GoalRequirement requirement) {
        return switch (requirement) {
            case TEMPORAL -> MISSING_REQUIRED_DATETIME_RESULT;
            case EXERCISE -> MISSING_REQUIRED_EXERCISE_RESULT;
            case MEAL -> MISSING_REQUIRED_MEAL_RESULT;
            case WEATHER -> MISSING_REQUIRED_WEATHER_RESULT;
            case LIFESTYLE -> throw new IllegalArgumentException(
                    "LIFESTYLE does not require a runtime capability result");
        };
    }

    private boolean needsUserInput(AgentObservation observation) {
        return NEEDS_USER_INPUT.equals(observation.structuredData().get("code"))
                && Boolean.parseBoolean(observation.structuredData().getOrDefault("recoverable", "false"));
    }

    private String safeUserInputSummary(AgentObservation observation) {
        String summary = observation.summary().strip();
        return summary.isBlank() ? "需要补充信息后才能继续生成计划" : summary;
    }

    private AgentObservation failure(
            AgentStep step,
            String code,
            String summary,
            boolean recoverable
    ) {
        return new AgentObservation(
                step.id(),
                false,
                summary,
                Map.of("code", code, "recoverable", Boolean.toString(recoverable))
        );
    }
}
