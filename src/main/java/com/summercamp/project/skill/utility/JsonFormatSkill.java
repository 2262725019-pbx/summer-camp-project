package com.summercamp.project.skill.utility;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summercamp.project.skill.BotSkill;
import com.summercamp.project.skill.SkillContext;
import com.summercamp.project.skill.SkillResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class JsonFormatSkill implements BotSkill {

    public static final String SKILL_NAME = "json-format";
    static final int MAX_JSON_CHARACTERS = 20_000;

    private static final Pattern TRIGGER = Pattern.compile(
            "^\\s*(?:json\\s*格式化|格式化\\s*json)\\s*[：:]?\\s*",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private final ObjectMapper objectMapper;

    public JsonFormatSkill(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return SKILL_NAME;
    }

    @Override
    public int priority() {
        return 90;
    }

    @Override
    public int matchScore(String text) {
        return text != null && TRIGGER.matcher(text).find() ? 100 : 0;
    }

    @Override
    public SkillResult execute(SkillContext context) {
        String json = extractJson(context.text());
        if (json.isEmpty()) {
            return SkillResult.waitingInput("请发送需要格式化的 JSON 内容。");
        }
        if (json.length() > MAX_JSON_CHARACTERS) {
            // 直接结束，不进入待补充状态，避免后续消息被 JSON 校验劫持
            return SkillResult.completed("JSON 内容过长，请控制在 20,000 个字符以内后重新发送。");
        }
        try {
            JsonNode value = objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(json);
            return SkillResult.completed(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value));
        } catch (JsonProcessingException exception) {
            // 直接结束，不进入待补充状态；用户可用"JSON格式化：{修正后的内容}"重试
            return SkillResult.completed(
                    "JSON 格式不合法，请检查后重新发送；示例：JSON格式化：{\"name\":\"夏令营\"}");
        }
    }

    private String extractJson(String text) {
        if (text == null) {
            return "";
        }
        Matcher matcher = TRIGGER.matcher(text);
        return matcher.find() ? text.substring(matcher.end()).strip() : text.strip();
    }
}
