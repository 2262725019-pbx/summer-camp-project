package com.summercamp.project.skill;

/**
 * A deterministic business workflow that is selected before ordinary LLM chat.
 */
public interface BotSkill {

    String name();

    default int priority() {
        return 0;
    }

    /**
     * Returns a positive score when the message should start this skill.
     */
    int matchScore(String text);

    SkillResult execute(SkillContext context);
}
