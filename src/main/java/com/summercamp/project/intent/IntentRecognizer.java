package com.summercamp.project.intent;

import com.summercamp.project.weather.WeatherPeriod;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class IntentRecognizer {

    private static final Logger LOGGER = LoggerFactory.getLogger(IntentRecognizer.class);
    private static final List<Pattern> IMAGE_GENERATION_PATTERNS = List.of(
            Pattern.compile("^(?:那|那么)?\\s*(?:请|麻烦)?\\s*(?:你)?\\s*(?:帮我|给我)\\s*"
                    + "(?:生成|创作|画|绘制)(?:一张|一幅|一个)?(?:图片|图像|图画)?"
                    + "[\\s：:，,、-]*(?<prompt>.*)$"),
            Pattern.compile("^(?:那|那么)?\\s*(?:请|麻烦)?\\s*(?:生成|创作)"
                    + "(?:一张|一幅|一个)?(?:图片|图像|图画|画)"
                    + "[\\s：:，,、-]*(?<prompt>.*)$"),
            Pattern.compile("^(?:那|那么)?\\s*(?:请|麻烦)?\\s*(?:画|绘制)"
                    + "(?:一张|一幅)(?:图片|图像|图画)?"
                    + "[\\s：:，,、-]*(?<prompt>.*)$"));
    private static final Pattern IMAGE_GENERATION_CAPABILITY_PATTERN = Pattern.compile(
            "^(?:请问)?\\s*(?:你)?\\s*(?:可以|能|能不能|会|支持|可不可以)\\s*"
                    + "(?:帮我|给我)?\\s*(?:生成|创作|画|绘制)\\s*"
                    + "(?:一张|一幅|一个)?\\s*(?:图片|图像|图画|画|图)"
                    + "\\s*(?:吗|么|呢|不|可以吗|行吗)?\\s*[。！!？?]*$");
    private static final List<Pattern> IMAGE_ANALYSIS_PATTERNS = List.of(
            Pattern.compile("^(?:那|那么)?\\s*(?:请|麻烦)?\\s*(?:你)?\\s*(?:帮我|给我)?\\s*"
                    + "(?:识别|分析|描述|解读|解释|看看|看一下|看懂)\\s*(?:一下)?\\s*"
                    + "(?:(?:我)?(?:刚才|之前)?(?:发|发送|给你)的)?\\s*"
                    + "(?:一张|这张|这个)?\\s*(?:图片|图像|照片|图)"
                    + "(?:里|中)?\\s*(?:有什么|是什么|的内容|内容是什么|讲了什么|显示什么)?"
                    + "\\s*[。！!？?]*$"),
            Pattern.compile("^(?:这张|这个|一张)?\\s*(?:图片|图像|照片|图)\\s*(?:里|中)?\\s*"
                    + "(?:有什么|是什么|内容是什么|讲了什么|显示什么|能看出什么)\\s*[。！!？?]*$"));
    private static final Pattern IMAGE_ANALYSIS_CAPABILITY_PATTERN = Pattern.compile(
            "^(?:请问)?\\s*(?:你)?\\s*(?:可以|能|能不能|会|支持|可不可以)\\s*"
                    + "(?:帮我|给我)?\\s*(?:识别|分析|看懂|理解|描述)\\s*"
                    + "(?:一张|这张|这个)?\\s*(?:图片|图像|照片|图)"
                    + "\\s*(?:吗|么|呢|不|可以吗|行吗)?\\s*[。！!？?]*$");
    private static final Pattern WEATHER_WORDS = Pattern.compile(
            "天气预报|天气|气温|温度|多少度|下雨|下雪|带伞|降雨|降雪|冷不冷|热不热|预报");
    private static final Pattern WEATHER_TIME_WORDS = Pattern.compile(
            "未来\\s*[三3]\\s*天|最近\\s*[三3]\\s*天|近\\s*[三3]\\s*天|[三3]\\s*天内|"
                    + "后天|明天|今天|今日|现在|当前|实时");
    private static final Pattern WEATHER_FILLER_WORDS = Pattern.compile(
            "请问|能不能|可不可以|可以|能否|麻烦|请|帮我|帮忙|给我|"
                    + "查询一下|查询|查一下|查查|查看|查|看看|告诉我|我想知道|想知道|"
                    + "一下|怎么样|如何|情况|会不会|是否|需要|要不要|能");
    private static final Pattern WEATHER_DISCUSSION_PATTERN = Pattern.compile(
            "天气之子|(?:今天)?天气(?:真|太|很|挺)(?:好|不错|糟|差)[。！!]*$");
    private static final Pattern ACTIONABLE_WORDS = Pattern.compile(
            "天气|气温|温度|下雨|下雪|带伞|预报|生成|创作|画|绘制|识别|分析|图片|照片|查询|帮我");
    private static final Pattern IMAGE_TOOL_WORDS = Pattern.compile(
            "生成.{0,8}(?:图片|图像|图画)|画(?:一张|一幅|个)?|绘制|创作.{0,8}(?:图片|图像|图画)");
    private static final Pattern TODO_TOOL_WORDS = Pattern.compile(
            "待办|记一下|记录一下|提醒我|完成第.{0,4}项|查看.{0,4}待办");
    private static final Pattern CALCULATOR_TOOL_WORDS = Pattern.compile(
            "计算|算一下|算出|等于多少|加上|减去|乘以|除以");
    private static final Pattern DATETIME_TOOL_WORDS = Pattern.compile(
            "现在几点|当前时间|今天几号|日期|星期几|周几");
    private static final Pattern QR_TOOL_WORDS = Pattern.compile("二维码|QR码|qr码");
    private static final Pattern PUNCTUATION = Pattern.compile("[\\s，,。.!！?？：:；;、]+$");
    private static final Set<String> HELP_COMMANDS = Set.of(
            "/help", "帮助", "使用说明", "怎么用", "功能列表", "你会什么", "有什么功能", "你有什么功能");

    private final IntentClassificationClient classificationClient;

    public IntentRecognizer(IntentClassificationClient classificationClient) {
        this.classificationClient = classificationClient;
    }

    public IntentResult recognize(String text) {
        String command = text == null ? "" : text.strip();
        if (command.isBlank()) {
            return IntentResult.chat();
        }
        String lower = command.toLowerCase(Locale.ROOT);
        if ("/clear".equals(lower) || "清除上下文".equals(command) || "清空上下文".equals(command)) {
            return IntentResult.simple(IntentType.CLEAR_CONTEXT);
        }
        String normalizedCommand = PUNCTUATION.matcher(lower).replaceAll("").strip();
        if (HELP_COMMANDS.contains(normalizedCommand)) {
            return IntentResult.simple(IntentType.HELP);
        }
        if (lower.equals("/image") || lower.startsWith("/image ")) {
            String prompt = command.length() <= 6 ? "" : command.substring(6).strip();
            return IntentResult.imageGeneration(prompt);
        }
        if (isMultiToolRequest(command)) {
            return IntentResult.chat();
        }
        if (IMAGE_GENERATION_CAPABILITY_PATTERN.matcher(command).matches()) {
            return IntentResult.imageGeneration("");
        }
        String imagePrompt = extractImagePrompt(command);
        if (imagePrompt != null) {
            return IntentResult.imageGeneration(imagePrompt);
        }
        if (isImageAnalysisRequest(command)) {
            return IntentResult.simple(IntentType.IMAGE_ANALYSIS_REQUEST);
        }
        if (WEATHER_WORDS.matcher(command).find()) {
            if (WEATHER_DISCUSSION_PATTERN.matcher(command).find()) {
                return IntentResult.chat();
            }
            return IntentResult.weather(extractWeatherLocation(command), weatherPeriod(command));
        }
        if (ACTIONABLE_WORDS.matcher(command).find()) {
            try {
                return classificationClient.classify(command).orElseGet(IntentResult::chat);
            } catch (RuntimeException exception) {
                LOGGER.warn("模型意图分类失败，按普通聊天处理：{}", exception.getMessage());
            }
        }
        return IntentResult.chat();
    }

    private boolean isMultiToolRequest(String command) {
        int toolCategories = 0;
        toolCategories += WEATHER_WORDS.matcher(command).find() ? 1 : 0;
        toolCategories += IMAGE_TOOL_WORDS.matcher(command).find() ? 1 : 0;
        toolCategories += TODO_TOOL_WORDS.matcher(command).find() ? 1 : 0;
        toolCategories += CALCULATOR_TOOL_WORDS.matcher(command).find() ? 1 : 0;
        toolCategories += DATETIME_TOOL_WORDS.matcher(command).find() ? 1 : 0;
        toolCategories += QR_TOOL_WORDS.matcher(command).find() ? 1 : 0;
        return toolCategories >= 2;
    }

    private String extractImagePrompt(String command) {
        for (Pattern pattern : IMAGE_GENERATION_PATTERNS) {
            Matcher matcher = pattern.matcher(command);
            if (matcher.matches()) {
                String prompt = PUNCTUATION.matcher(matcher.group("prompt").strip())
                        .replaceAll("")
                        .strip();
                if (prompt.matches("(?:吗|么|呢|吧|呀|啊|可以吗|行吗|不)?")) {
                    return "";
                }
                return prompt;
            }
        }
        return null;
    }

    private boolean isImageAnalysisRequest(String command) {
        if (IMAGE_ANALYSIS_CAPABILITY_PATTERN.matcher(command).matches()) {
            return true;
        }
        return IMAGE_ANALYSIS_PATTERNS.stream().anyMatch(pattern -> pattern.matcher(command).matches());
    }

    String extractWeatherLocation(String command) {
        String location = WEATHER_TIME_WORDS.matcher(command).replaceAll(" ");
        location = WEATHER_WORDS.matcher(location).replaceAll(" ");
        location = WEATHER_FILLER_WORDS.matcher(location).replaceAll(" ");
        location = location.replaceAll("的", " ")
                .replaceAll("[\\s，,。.!！?？：:；;、]+", " ")
                .strip();
        location = PUNCTUATION.matcher(location).replaceAll("").strip();
        return location.replaceAll("(?:需要|要不要|要|会|吗|呢|呀|吧)+$", "").strip();
    }

    private WeatherPeriod weatherPeriod(String command) {
        String compact = command.replaceAll("\\s+", "");
        if (compact.contains("未来三天") || compact.contains("未来3天")
                || compact.contains("最近三天") || compact.contains("最近3天")
                || compact.contains("近三天") || compact.contains("近3天")
                || compact.contains("三天内") || compact.contains("3天内")) {
            return WeatherPeriod.THREE_DAYS;
        }
        if (compact.contains("后天")) {
            return WeatherPeriod.DAY_AFTER_TOMORROW;
        }
        if (compact.contains("明天")) {
            return WeatherPeriod.TOMORROW;
        }
        if (compact.contains("今天") || compact.contains("今日")) {
            return WeatherPeriod.TODAY;
        }
        return WeatherPeriod.CURRENT;
    }
}
