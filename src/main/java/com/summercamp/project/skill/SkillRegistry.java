package com.summercamp.project.skill;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class SkillRegistry {

    private final List<BotSkill> skills;
    private final Map<String, BotSkill> skillsByName;

    public SkillRegistry(List<BotSkill> skills) {
        Map<String, BotSkill> byName = new LinkedHashMap<>();
        for (BotSkill skill : skills) {
            String name = skill.name().strip();
            if (name.isBlank()) {
                throw new IllegalStateException("Skill 名称不能为空");
            }
            if (byName.putIfAbsent(name, skill) != null) {
                throw new IllegalStateException("存在重复的 Skill 名称：" + name);
            }
        }
        this.skills = List.copyOf(skills);
        this.skillsByName = Map.copyOf(byName);
    }

    public Optional<Match> match(String text) {
        return skills.stream()
                .map(skill -> new Match(skill, Math.max(0, skill.matchScore(text))))
                .filter(match -> match.score() > 0)
                .max(Comparator.comparingInt(Match::score)
                        .thenComparingInt(match -> match.skill().priority())
                        .thenComparing(match -> match.skill().name(), Comparator.reverseOrder()));
    }

    public Optional<BotSkill> findByName(String name) {
        return Optional.ofNullable(skillsByName.get(name));
    }

    public record Match(BotSkill skill, int score) {
    }
}
