package com.summercamp.project.agent;

import com.summercamp.project.skill.SkillRegistry;
import org.springframework.stereotype.Component;

@Component
public final class MealSkillAgentActionHandler extends AbstractSkillAgentActionHandler {
    public MealSkillAgentActionHandler(SkillRegistry skillRegistry) {
        super(AgentAction.RUN_MEAL_SKILL, "muscle-gain-meal-plan", skillRegistry);
    }
}
