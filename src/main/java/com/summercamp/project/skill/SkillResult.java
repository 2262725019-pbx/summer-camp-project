package com.summercamp.project.skill;

public record SkillResult(String reply, Status status) {

    public SkillResult {
        reply = reply == null ? "" : reply.strip();
        if (reply.isBlank()) {
            throw new IllegalArgumentException("Skill 回复不能为空");
        }
    }

    public static SkillResult completed(String reply) {
        return new SkillResult(reply, Status.COMPLETED);
    }

    public static SkillResult waitingInput(String reply) {
        return new SkillResult(reply, Status.WAITING_INPUT);
    }

    public enum Status {
        COMPLETED,
        WAITING_INPUT
    }
}
