package com.summercamp.project.agent;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public final class SynthesizeActionHandler implements AgentActionHandler {
    public static final String SYNTHESIS_COMPLETED = "SYNTHESIS_COMPLETED";
    public static final String SYNTHESIS_FAILED = "SYNTHESIS_FAILED";
    public static final int MAX_SYNTHESIS_REPAIR_ATTEMPTS = 1;
    public static final int MAX_REPAIR_PREVIOUS_ANSWER_CHARS = 5_000;
    public static final int MAX_REPAIR_INSTRUCTION_CHARS = 6_000;

    private static final Logger LOGGER = LoggerFactory.getLogger(SynthesizeActionHandler.class);

    private final AgentSynthesisContextBuilder contextBuilder;
    private final AgentSynthesisClient synthesisClient;
    private final AgentFinalPlanValidator finalPlanValidator;
    private final AgentDeterministicFinalRenderer deterministicRenderer;
    private final AgentTransientFailureClassifier transientFailureClassifier;

    @Autowired
    public SynthesizeActionHandler(
            AgentSynthesisContextBuilder contextBuilder,
            AgentSynthesisClient synthesisClient,
            AgentFinalPlanValidator finalPlanValidator,
            AgentDeterministicFinalRenderer deterministicRenderer
    ) {
        this.contextBuilder = Objects.requireNonNull(contextBuilder, "contextBuilder must not be null");
        this.synthesisClient = Objects.requireNonNull(synthesisClient, "synthesisClient must not be null");
        this.finalPlanValidator = Objects.requireNonNull(
                finalPlanValidator, "finalPlanValidator must not be null");
        this.deterministicRenderer = Objects.requireNonNull(
                deterministicRenderer, "deterministicRenderer must not be null");
        this.transientFailureClassifier = new AgentTransientFailureClassifier();
    }

    public SynthesizeActionHandler(
            AgentSynthesisContextBuilder contextBuilder,
            AgentSynthesisClient synthesisClient,
            AgentFinalPlanValidator finalPlanValidator
    ) {
        this(
                contextBuilder,
                synthesisClient,
                finalPlanValidator,
                new AgentDeterministicFinalRenderer());
    }

    public SynthesizeActionHandler(
            AgentSynthesisContextBuilder contextBuilder,
            AgentSynthesisClient synthesisClient
    ) {
        this(
                contextBuilder,
                synthesisClient,
                new AgentFinalPlanValidator(),
                new AgentDeterministicFinalRenderer());
    }

    @Override
    public AgentAction action() {
        return AgentAction.SYNTHESIZE;
    }

    @Override
    public AgentObservation execute(AgentStep step, AgentExecutionContext context) {
        if (step.action() != AgentAction.SYNTHESIZE) {
            return failure(step, "最终汇总步骤类型不匹配");
        }
        List<AgentStep> validations = context.plan().steps().stream()
                .filter(candidate -> candidate.action() == AgentAction.VALIDATE)
                .toList();
        if (validations.size() != 1 || !step.dependsOn().contains(validations.getFirst().id())) {
            return failure(step, "缺少有效的运行时校验依赖");
        }
        AgentStep validation = validations.getFirst();
        AgentObservation validationResult = context.state().findObservation(validation.id()).orElse(null);
        if (context.state().statusOf(validation.id()) != AgentStepStatus.COMPLETED
                || validationResult == null
                || !validationResult.success()
                || !ValidateActionHandler.VALIDATION_PASSED.equals(
                        validationResult.structuredData().get("code"))) {
            return failure(step, "运行时校验未通过，已阻止最终汇总");
        }

        try {
            AgentSynthesisContextBuilder.BuiltContext builtContext = contextBuilder.buildDetailed(
                    context.originalGoal(), context.plan(), context.state());
            String synthesisContext = builtContext.context();
            context.metrics().recordSynthesisContext(builtContext.breakdown());
            AgentSynthesisResult synthesisResult;
            try {
                synthesisResult = synthesizeOnce(context, synthesisContext);
            } catch (RuntimeException failure) {
                AgentFallbackReason reason = transientFailureClassifier.classify(failure)
                        .orElse(null);
                if (reason == null) {
                    throw failure;
                }
                return deterministicFallback(step, context, synthesisContext, reason);
            }
            FinalPlanValidationResult finalValidation = validate(
                    context, synthesisContext, synthesisResult);
            if (!finalValidation.valid()) {
                String repairInstruction = repairInstruction(
                        synthesisResult.answer(), finalValidation.issues());
                context.metrics().recordSynthesisRepairTriggered(repairInstruction.length());
                LOGGER.info("Agent synthesis repair triggered");
                AgentSynthesisResult repairedResult;
                try {
                    repairedResult = synthesizeOnce(
                            context, synthesisContext + repairInstruction);
                } catch (RuntimeException failure) {
                    AgentFallbackReason reason = transientFailureClassifier.classify(failure)
                            .orElse(null);
                    if (reason == null) {
                        throw failure;
                    }
                    return deterministicFallback(step, context, synthesisContext, reason);
                }
                FinalPlanValidationResult repairedValidation = validate(
                        context, synthesisContext, repairedResult);
                if (!repairedValidation.valid()) {
                    return deterministicFallback(
                            step,
                            context,
                            synthesisContext,
                            AgentFallbackReason.INVALID_SYNTHESIS_AFTER_REPAIR);
                }
                context.metrics().recordSynthesisRepairSucceeded();
                synthesisResult = repairedResult;
            }
            return new AgentObservation(
                    step.id(),
                    true,
                    synthesisResult.answer(),
                    Map.of("code", SYNTHESIS_COMPLETED)
            );
        } catch (RuntimeException exception) {
            return failure(step, "最终汇总暂时失败，请稍后重试");
        }
    }

    private AgentObservation deterministicFallback(
            AgentStep step,
            AgentExecutionContext context,
            String synthesisContext,
            AgentFallbackReason reason
    ) {
        context.metrics().recordDeterministicSynthesisFallback(reason);
        LOGGER.warn("Agent synthesis deterministic fallback: reason={}", reason);
        AgentSynthesisResult rendered = deterministicRenderer.render(
                context.originalGoal(), context.plan(), context.state(), synthesisContext);
        FinalPlanValidationResult validation = validate(
                context, synthesisContext, rendered);
        if (!validation.valid()) {
            return failure(step, "确定性最终方案一致性校验未通过，已阻止返回");
        }
        return new AgentObservation(
                step.id(),
                true,
                rendered.answer(),
                Map.of("code", SYNTHESIS_COMPLETED, "deterministic", "true"));
    }

    private AgentSynthesisResult synthesizeOnce(
            AgentExecutionContext context,
            String synthesisContext
    ) {
        long synthesisStartedAt = System.nanoTime();
        try {
            return synthesisClient.synthesize(
                    context.originalGoal(),
                    synthesisContext,
                    context.metrics().withLlmPhase(AgentRunMetrics.LlmPhase.SYNTHESIS));
        } finally {
            context.metrics().recordSynthesisDuration(System.nanoTime() - synthesisStartedAt);
        }
    }

    private FinalPlanValidationResult validate(
            AgentExecutionContext context,
            String synthesisContext,
            AgentSynthesisResult synthesisResult
    ) {
        FinalPlanValidationResult result = finalPlanValidator.validate(
                context.originalGoal(),
                context.plan(),
                context.state(),
                synthesisContext,
                synthesisResult);
        context.metrics().recordFinalValidationAttempt(result.valid());
        LOGGER.info("Agent final validation: valid={}, issues={}",
                result.valid(), result.issues());
        return result;
    }

    private String repairInstruction(
            String previousAnswer,
            List<FinalPlanValidationIssueCode> issues
    ) {
        String issueNames = issues.stream()
                .map(Enum::name)
                .collect(Collectors.joining(","));
        String corrections = issues.stream()
                .map(this::correctionFor)
                .distinct()
                .collect(Collectors.joining("\n"));
        String previous = safeTruncate(
                previousAnswer == null ? "" : previousAnswer,
                MAX_REPAIR_PREVIOUS_ANSWER_CHARS);
        String instruction = """

                [FINAL_PLAN_REPAIR，仅用于本次最终汇总修复，不得向用户展示]
                VALIDATION_ISSUES=%s
                REQUIRED_CORRECTIONS:
                %s
                仅修复上述一致性问题。继续严格使用前述相同事实和天气边界；不得调用或假设新工具事实。
                PREVIOUS_ANSWER_EXCERPT:
                %s
                [END_FINAL_PLAN_REPAIR]
                """.formatted(issueNames, corrections, previous);
        if (instruction.length() > MAX_REPAIR_INSTRUCTION_CHARS) {
            throw new IllegalStateException("Repair instruction exceeds hard safety limit");
        }
        return instruction;
    }

    private String correctionFor(FinalPlanValidationIssueCode issue) {
        return switch (issue) {
            case MISSING_PLAN_DATE -> "-逐一覆盖 PLAN_DATE_LABELS 中的每个日期，不得遗漏。";
            case WRONG_WEEKDAY -> "-按 PLAN_DATE_LABELS 修正日期对应的星期。";
            case CONCRETE_WEATHER_OUTSIDE_SCOPE ->
                    "-从 WEATHER_UNQUERIED_FROM 起不得绑定具体天气、温度或风力；改为未查询提示。";
            case SYNTHESIS_ENVELOPE_INVALID ->
                    "-返回严格合法的 Agent Synthesis JSON，root 仅包含 answer 和 audit。";
            case TRAINING_AUDIT_MISSING ->
                    "-补齐 audit.trainingDates 及目标明确要求的 sessionDurationMinutesByDate。";
            case TRAINING_FREQUENCY_MISMATCH ->
                    "-正式训练日期数量必须与 TRAINING_FREQUENCY_PER_WEEK 精确一致且不重复。";
            case TRAINING_DATE_OUTSIDE_PLAN ->
                    "-所有正式训练日期必须位于 PLAN_START_DATE 至 PLAN_END_DATE。";
            case TRAINING_DATE_NOT_PRESENT_IN_ANSWER ->
                    "-在 answer 计划中覆盖 audit.trainingDates 声明的每个日期。";
            case TRAINING_DURATION_MISSING ->
                    "-为每个正式训练日填写整次 session 总时长。";
            case TRAINING_DURATION_DATE_MISMATCH ->
                    "-sessionDurationMinutesByDate 的日期必须与 trainingDates 完全一致。";
            case TRAINING_DURATION_EXCEEDED ->
                    "-每个正式训练 session 总时长不得超过 TRAINING_SESSION_TOTAL_MINUTES。";
        };
    }

    private String safeTruncate(String value, int maximum) {
        if (value.length() <= maximum) {
            return value;
        }
        int end = maximum;
        if (Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }

    private AgentObservation failure(AgentStep step, String summary) {
        return new AgentObservation(
                step.id(),
                false,
                summary,
                Map.of("code", SYNTHESIS_FAILED, "recoverable", "false")
        );
    }
}
