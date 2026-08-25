package com.summercamp.project.skill.entertainment;

import com.summercamp.project.skill.BotSkill;
import com.summercamp.project.skill.SkillContext;
import com.summercamp.project.skill.SkillResult;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

@Component
public class ColdJokeSkill implements BotSkill {

    public static final String SKILL_NAME = "cold-joke";

    private static final List<String> DIRECT_TERMS = List.of(
            "冷笑话", "冷段子", "讲个笑话", "讲一个笑话", "说个笑话", "说一个笑话", "来个笑话", "来一个笑话");

    private static final List<String> JOKES = List.of(
            "为什么程序员分不清万圣节和圣诞节？因为十月三十一等于十二月二十五。",
            "有一只北极熊很无聊，于是开始拔自己的毛。拔完以后，它说：真冷啊。",
            "从前有一根火柴走在路上，突然觉得头很痒，挠了一下就把自己点着了。",
            "为什么数学书总是不开心？因为它有太多问题。",
            "一只小鸭子去买东西，店员问它怎么付款。小鸭子说：刷鸭。",
            "什么水果最忙？芒果，因为它一直在忙。",
            "为什么电脑感冒了？因为它打开了太多窗口。",
            "有一天绿豆摔了一跤，流血了，它就变成了红豆。",
            "为什么海边不能讲冷笑话？因为会引起海笑。",
            "什么动物最容易摔倒？狐狸，因为它脚滑。",
            "有一只羊在吃草，一只狼来了却没有吃它。因为那只狼想吃素。",
            "为什么闹钟总是很累？因为它每天都要把别人叫醒。"
    );

    private final ConcurrentMap<String, AtomicInteger> nextJokeByUser = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return SKILL_NAME;
    }

    @Override
    public int priority() {
        return 80;
    }

    @Override
    public int matchScore(String text) {
        String normalized = normalize(text);
        int longestTerm = DIRECT_TERMS.stream()
                .filter(normalized::contains)
                .mapToInt(String::length)
                .max()
                .orElse(0);
        return longestTerm == 0 ? 0 : 80 + longestTerm;
    }

    @Override
    public SkillResult execute(SkillContext context) {
        String userKey = context.userId().isBlank() ? "anonymous" : context.userId();
        int sequence = nextJokeByUser.computeIfAbsent(userKey, ignored -> new AtomicInteger()).getAndIncrement();
        int index = Math.floorMod(userKey.hashCode() + sequence, JOKES.size());
        return SkillResult.completed("给你讲一个冷笑话：\n" + JOKES.get(index));
    }

    private String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{S}\\s]+", "");
    }
}
