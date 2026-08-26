package com.summercamp.project.agent;

import com.summercamp.project.skill.SkillRegistry;
import com.summercamp.project.skill.SkillTrustedContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public final class ExerciseSkillAgentActionHandler extends AbstractSkillAgentActionHandler {
    private static final Logger LOGGER =
            LoggerFactory.getLogger(ExerciseSkillAgentActionHandler.class);
    private final TrustedWeatherObservationResolver weatherResolver =
            new TrustedWeatherObservationResolver();

    public ExerciseSkillAgentActionHandler(SkillRegistry skillRegistry) {
        super(AgentAction.RUN_EXERCISE_SKILL, "exercise-health-advice", skillRegistry);
    }

    @Override
    protected SkillTrustedContext trustedContext(
            AgentStep step,
            AgentExecutionContext context
    ) {
        TrustedWeatherObservationResolver.Decision decision = weatherResolver.resolve(step, context);
        LOGGER.info(
                "Trusted weather reuse decision: consumer=RUN_EXERCISE_SKILL, "
                        + "eligible={}, reason={}",
                decision.eligible(),
                decision.reason());
        return decision.observation()
                .map(observation -> {
                    context.metrics().recordWeatherReuseEligible();
                    return SkillTrustedContext.withWeather(observation);
                })
                .orElseGet(SkillTrustedContext::empty);
    }
}
