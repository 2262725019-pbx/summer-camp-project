package com.summercamp.project.agent;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public final class SynthesizeActionHandler implements AgentActionHandler {
    public static final String SYNTHESIS_COMPLETED = "SYNTHESIS_COMPLETED";
    public static final String SYNTHESIS_FAILED = "SYNTHESIS_FAILED";

    private final AgentSynthesisContextBuilder contextBuilder;
    private final AgentSynthesisClient synthesisClient;

    public SynthesizeActionHandler(
            AgentSynthesisContextBuilder contextBuilder,
            AgentSynthesisClient synthesisClient
    ) {
        this.contextBuilder = Objects.requireNonNull(contextBuilder, "contextBuilder must not be null");
        this.synthesisClient = Objects.requireNonNull(synthesisClient, "synthesisClient must not be null");
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
            String synthesisContext = contextBuilder.build(
                    context.originalGoal(), context.plan(), context.state());
            String answer = synthesisClient.synthesize(context.originalGoal(), synthesisContext);
            if (answer == null || answer.isBlank()) {
                return failure(step, "最终汇总服务未返回可用内容");
            }
            return new AgentObservation(
                    step.id(),
                    true,
                    answer.strip(),
                    Map.of("code", SYNTHESIS_COMPLETED)
            );
        } catch (RuntimeException exception) {
            return failure(step, "最终汇总暂时失败，请稍后重试");
        }
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
