package com.summercamp.project.agent;

import java.util.Objects;

public record AgentGoalMatch(Status status, String goal) {
    public enum Status {
        MATCHED,
        EMPTY_GOAL,
        NOT_MATCHED
    }

    public AgentGoalMatch {
        status = Objects.requireNonNull(status, "status must not be null");
        goal = goal == null ? "" : goal.strip();
        if (status == Status.MATCHED && goal.isBlank()) {
            throw new IllegalArgumentException("matched goal must not be blank");
        }
        if (status != Status.MATCHED && !goal.isBlank()) {
            throw new IllegalArgumentException("unmatched result must not contain a goal");
        }
    }

    public static AgentGoalMatch matched(String goal) {
        return new AgentGoalMatch(Status.MATCHED, goal);
    }

    public static AgentGoalMatch emptyGoal() {
        return new AgentGoalMatch(Status.EMPTY_GOAL, "");
    }

    public static AgentGoalMatch notMatched() {
        return new AgentGoalMatch(Status.NOT_MATCHED, "");
    }
}
