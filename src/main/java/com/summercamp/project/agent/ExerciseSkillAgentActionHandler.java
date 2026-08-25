package com.summercamp.project.agent;

import com.summercamp.project.skill.SkillRegistry;
import org.springframework.stereotype.Component;

@Component
public final class ExerciseSkillAgentActionHandler extends AbstractSkillAgentActionHandler {
    public ExerciseSkillAgentActionHandler(SkillRegistry skillRegistry) {
        super(AgentAction.RUN_EXERCISE_SKILL, "exercise-health-advice", skillRegistry);
    }
}
