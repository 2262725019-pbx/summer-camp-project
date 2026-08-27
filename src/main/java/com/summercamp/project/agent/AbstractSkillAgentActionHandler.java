package com.summercamp.project.agent;

import com.summercamp.project.skill.BotSkill;
import com.summercamp.project.skill.SkillContext;
import com.summercamp.project.skill.SkillExecutionMode;
import com.summercamp.project.skill.SkillRegistry;
import com.summercamp.project.skill.SkillResult;
import com.summercamp.project.skill.SkillTrustedContext;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

abstract class AbstractSkillAgentActionHandler implements AgentActionHandler {
    static final int MAX_SKILL_REQUEST_CHARS = 8_000;
    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractSkillAgentActionHandler.class);

    private final AgentAction action;
    private final String skillName;
    private final SkillRegistry skillRegistry;
    private final AgentActionInputValidator inputValidator = new AgentActionInputValidator();

    AbstractSkillAgentActionHandler(
            AgentAction action,
            String skillName,
            SkillRegistry skillRegistry
    ) {
        this.action = Objects.requireNonNull(action, "action must not be null");
        this.skillName = Objects.requireNonNull(skillName, "skillName must not be null");
        this.skillRegistry = Objects.requireNonNull(skillRegistry, "skillRegistry must not be null");
    }

    @Override
    public final AgentAction action() {
        return action;
    }

    @Override
    public final AgentObservation execute(AgentStep step, AgentExecutionContext context) {
        if (step.action() != action) {
            return invalidInput(step, "Handler action does not match step action");
        }
        List<String> errors = inputValidator.validate(step);
        if (!errors.isEmpty()) {
            return invalidInput(step, String.join("; ", errors));
        }

        Optional<BotSkill> availableSkill = skillRegistry.findByName(skillName);
        if (availableSkill.isEmpty()) {
            return new AgentObservation(
                    step.id(),
                    false,
                    "Required skill is not registered: " + skillName,
                    Map.of("code", "SKILL_NOT_FOUND", "skill", skillName)
            );
        }

        String request = buildSkillRequest(
                context.originalGoal(),
                step.inputs().getOrDefault("request", ""),
                context.resumeSupplementFor(step.id()).orElse(""));
        SkillTrustedContext trustedContext = trustedContext(step, context);
        context.metrics().recordSkillCall(action);
        if (trustedContext.weatherObservation().isPresent()) {
            context.metrics().recordWeatherReuseApplied();
            LOGGER.info("Agent capability reuse: capability=WEATHER, "
                    + "consumer=RUN_EXERCISE_SKILL, applied=true");
        }
        long skillStartedAt = System.nanoTime();
        SkillResult result;
        try {
            AgentRunMetrics.LlmPhase llmPhase = action == AgentAction.RUN_EXERCISE_SKILL
                    ? AgentRunMetrics.LlmPhase.EXERCISE_SKILL
                    : AgentRunMetrics.LlmPhase.SKILL;
            result = availableSkill.orElseThrow().execute(new SkillContext(
                    context.userId(),
                    request,
                    context.history(),
                    context.voiceMessage(),
                    context.metrics().withLlmPhase(llmPhase),
                    trustedContext,
                    SkillExecutionMode.AGENT
            ));
        } finally {
            context.metrics().recordSkillDuration(action, System.nanoTime() - skillStartedAt);
        }
        if (result.status() == SkillResult.Status.WAITING_INPUT) {
            return new AgentObservation(
                    step.id(),
                    false,
                    result.reply(),
                    Map.of(
                            "code", "NEEDS_USER_INPUT",
                            "recoverable", "true",
                            "skill", skillName,
                            "status", result.status().name()
                    )
            );
        }
        return new AgentObservation(
                step.id(),
                true,
                result.reply(),
                Map.of(
                        "skill", skillName,
                        "status", result.status().name(),
                        "reply", result.reply()
                )
        );
    }

    protected SkillTrustedContext trustedContext(
            AgentStep step,
            AgentExecutionContext context
    ) {
        return SkillTrustedContext.empty();
    }

    private String buildSkillRequest(
            String originalGoal,
            String stepRequest,
            String resumeSupplement
    ) {
        String canonicalGoal = originalGoal == null ? "" : originalGoal.strip();
        String supplement = stepRequest == null ? "" : stepRequest.strip();
        String request;
        if (supplement.isBlank()) {
            request = canonicalGoal;
        } else if (canonicalGoal.isBlank()) {
            request = supplement;
        } else {
            request = canonicalGoal + "\n\n当前 Agent 步骤补充：" + supplement;
        }
        String latestSupplement = resumeSupplement == null ? "" : resumeSupplement.strip();
        if (!latestSupplement.isBlank()) {
            request = request + "\n\n用户最新补充：" + latestSupplement;
        }
        if (request.length() <= MAX_SKILL_REQUEST_CHARS) {
            return request;
        }
        int end = MAX_SKILL_REQUEST_CHARS;
        if (Character.isHighSurrogate(request.charAt(end - 1))) {
            end--;
        }
        return request.substring(0, end);
    }

    private AgentObservation invalidInput(AgentStep step, String summary) {
        return new AgentObservation(
                step.id(),
                false,
                summary,
                Map.of("code", "INVALID_INPUT", "skill", skillName)
        );
    }
}
