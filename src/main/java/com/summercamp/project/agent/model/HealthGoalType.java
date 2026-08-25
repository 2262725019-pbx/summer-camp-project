package com.summercamp.project.agent.model;

public enum HealthGoalType {
    MUSCLE_GAIN("增肌"),
    FAT_LOSS("减脂"),
    FITNESS("提升体能"),
    HEALTHY_ROUTINE("规律作息");

    private final String chineseName;

    HealthGoalType(String chineseName) {
        this.chineseName = chineseName;
    }

    public String chineseName() {
        return chineseName;
    }
}
