package com.summercamp.project.agent;

import com.summercamp.project.skill.BotSkill;
import com.summercamp.project.skill.SkillContext;
import com.summercamp.project.skill.SkillRegistry;
import com.summercamp.project.skill.SkillResult;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

abstract class AbstractSkillAgentActionHandler implements AgentActionHandler {
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

        String request = step.inputs().getOrDefault("request", "").strip();
        if (request.isBlank()) {
            request = context.originalGoal();
        }
        SkillResult result = availableSkill.orElseThrow().execute(new SkillContext(
                context.userId(),
                request,
                context.history(),
                context.voiceMessage()
        ));
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

    private AgentObservation invalidInput(AgentStep step, String summary) {
        return new AgentObservation(
                step.id(),
                false,
                summary,
                Map.of("code", "INVALID_INPUT", "skill", skillName)
        );
    }
}
