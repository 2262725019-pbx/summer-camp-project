package com.summercamp.project.conversation;

/** Strict V1 allowlist for low-risk, deterministic session facts. */
public enum SessionFactKey {
    LOCATION("演示地点"),
    DEMO_FOCUS("答辩重点"),
    DEMO_ORDER("演示顺序"),
    PREFERRED_BACKEND_LANGUAGE("后端语言"),
    EXERCISE_GOAL("运动目标"),
    EXERCISE_PREFERENCE("运动偏好"),
    TRAINING_FREQUENCY_PER_WEEK("每周训练次数"),
    TRAINING_DURATION_MINUTES("每次训练分钟数"),
    DAILY_MEAL_COUNT("每日餐数");

    private final String promptLabel;

    SessionFactKey(String promptLabel) {
        this.promptLabel = promptLabel;
    }

    public String promptLabel() {
        return promptLabel;
    }
}
