package com.summercamp.project.agent;

public enum GoalRequirement {
    WEATHER(AgentAction.GET_WEATHER),
    EXERCISE(AgentAction.RUN_EXERCISE_SKILL),
    MEAL(AgentAction.RUN_MEAL_SKILL),
    LIFESTYLE(null);

    private final AgentAction requiredAction;

    GoalRequirement(AgentAction requiredAction) {
        this.requiredAction = requiredAction;
    }

    AgentAction requiredAction() {
        return requiredAction;
    }
}
