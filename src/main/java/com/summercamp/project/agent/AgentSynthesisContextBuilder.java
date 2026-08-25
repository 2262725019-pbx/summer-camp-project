package com.summercamp.project.agent;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public final class AgentSynthesisContextBuilder {
    public static final int MAX_OBSERVATION_CHARS = 4_000;
    public static final int MAX_TOTAL_CHARS = 20_000;

    private static final Set<AgentAction> INCLUDED_ACTIONS = EnumSet.of(
            AgentAction.GET_DATETIME,
            AgentAction.GET_WEATHER,
            AgentAction.RETRIEVE_KNOWLEDGE,
            AgentAction.RUN_EXERCISE_SKILL,
            AgentAction.RUN_MEAL_SKILL,
            AgentAction.CALCULATE,
            AgentAction.CREATE_TODO,
            AgentAction.VALIDATE
    );
    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "(?i).*(api.?key|authorization|password|secret|access.?token|private.?key).*"
    );
    private static final Pattern BINARY_KEY = Pattern.compile(
            "(?i).*(binary|base64|bytes|image|audio|video).*"
    );
    private static final Pattern INTERNAL_KEY = Pattern.compile(
            "(?i)(code|recoverable|status|stack.?trace|debug|exception)"
    );
    private static final Pattern INLINE_SECRET = Pattern.compile(
            "(?i)(api.?key|authorization|password|secret|access.?token)\\s*[:=]\\s*[^\\s,;]+"
    );
    private static final Pattern TRAINING_FREQUENCY = Pattern.compile(
            "每周训练\\s*[:：]?\\s*(\\d{1,2})\\s*次"
    );
    private static final Pattern TRAINING_DURATION = Pattern.compile(
            "每次(?:训练)?\\s*[:：]?\\s*(\\d{1,3})\\s*分钟"
    );
    private static final Pattern PLAN_DAYS = Pattern.compile(
            "(?:未来|接下来)\\s*(\\d{1,2}|七)\\s*天"
    );
    private static final Pattern ISO_DATE = Pattern.compile(
            "(?<!\\d)(20\\d{2}-\\d{2}-\\d{2})(?!\\d)"
    );
    private static final List<GoalRequirement> REQUIREMENT_ORDER = List.of(
            GoalRequirement.EXERCISE,
            GoalRequirement.MEAL,
            GoalRequirement.WEATHER,
            GoalRequirement.LIFESTYLE
    );
    private static final List<AgentAction> CAPABILITY_ORDER = List.of(
            AgentAction.GET_DATETIME,
            AgentAction.GET_WEATHER,
            AgentAction.RETRIEVE_KNOWLEDGE,
            AgentAction.RUN_EXERCISE_SKILL,
            AgentAction.RUN_MEAL_SKILL,
            AgentAction.CALCULATE,
            AgentAction.CREATE_TODO
    );

    private final GoalRequirementExtractor requirementExtractor = new GoalRequirementExtractor();

    public String build(String originalGoal, AgentPlan plan, AgentStateView state) {
        if (originalGoal == null || originalGoal.isBlank()) {
            throw new IllegalArgumentException("originalGoal must not be blank");
        }
        if (plan == null || state == null) {
            throw new IllegalArgumentException("plan and state must not be null");
        }

        StringBuilder result = new StringBuilder(MAX_TOTAL_CHARS);
        Set<GoalRequirement> requiredDomains = requirementExtractor.extract(originalGoal);
        Set<AgentAction> completedCapabilities = completedCapabilities(plan, state);
        SynthesisConstraints constraints = constraints(originalGoal, plan, state);
        appendWithinTotal(result, groundingMetadata(
                requiredDomains,
                completedCapabilities,
                weatherScope(plan, state),
                constraints
        ));
        appendWithinTotal(result, "\n用户目标与明确数字约束：" + sanitize(originalGoal)
                + "\n\n已验证的真实执行结果：\n");
        for (AgentStep step : plan.steps()) {
            if (!INCLUDED_ACTIONS.contains(step.action())
                    || state.statusOf(step.id()) != AgentStepStatus.COMPLETED) {
                continue;
            }
            AgentObservation observation = state.findObservation(step.id()).orElse(null);
            if (observation == null || !observation.success()) {
                continue;
            }
            String block = observationBlock(step, observation);
            appendWithinTotal(result, safeTruncate(block, MAX_OBSERVATION_CHARS));
            if (result.length() >= MAX_TOTAL_CHARS) {
                break;
            }
        }
        return result.toString();
    }

    private String groundingMetadata(
            Set<GoalRequirement> requiredDomains,
            Set<AgentAction> completedCapabilities,
            String weatherScope,
            SynthesisConstraints constraints
    ) {
        StringBuilder metadata = new StringBuilder();
        metadata.append("[GROUNDING_METADATA，仅供内部最终汇总，不得向用户展示]\n")
                .append("REQUIRED_DOMAINS:\n");
        appendNames(metadata, REQUIREMENT_ORDER.stream()
                .filter(requiredDomains::contains)
                .map(Enum::name)
                .toList());
        metadata.append("COMPLETED_CAPABILITIES:\n");
        appendNames(metadata, CAPABILITY_ORDER.stream()
                .filter(completedCapabilities::contains)
                .map(Enum::name)
                .toList());
        metadata.append("WEATHER_SCOPE:\n")
                .append(weatherScope)
                .append('\n');
        appendConstraintMetadata(metadata, constraints);
        metadata.append("FACT_SOURCE_PRIORITY:\n")
                .append("WEATHER_FACTS=GET_WEATHER\n")
                .append("FORMAL_EXERCISE_PLAN=RUN_EXERCISE_SKILL\n")
                .append("FORMAL_MEAL_PLAN=RUN_MEAL_SKILL\n")
                .append("CALCULATED_VALUES=CALCULATE\n")
                .append("[最终汇总强约束]\n")
                .append("只能把成功 Observation 中存在的数据描述为已查询、已计算或已生成。\n")
                .append("不得补写 COMPLETED_CAPABILITIES 中不存在的详细 Skill 方案。\n")
                .append("正式运动和饮食分别以成功 Exercise/Meal Skill Observation 为主要来源；"
                        + "天气以 GET_WEATHER 为唯一事实来源。只能整理、重排、合并、解释。\n")
                .append("如果真实天气与运动场地冲突，保留运动内容但按天气事实调整为室内等价方案；"
                        + "所有章节必须使用同一调整结果。\n")
                .append("每日安排优先写完整日期（星期），不得只写星期。\n")
                .append("必须严格遵守用户目标中的训练频率、时长、餐数等数字约束，并确保章节一致。"
                        + "非训练日活动必须明确标为恢复/日常活动，不计入正式训练。\n")
                .append("输出前逐项检查日期范围、日期星期对应、正式训练次数与时长、天气运动冲突、"
                        + "Skill 结果来源、未查询天气范围及跨章节数字一致性。\n");
        return metadata.toString();
    }

    private void appendConstraintMetadata(StringBuilder metadata, SynthesisConstraints constraints) {
        if (constraints.planStartDate() != null) {
            metadata.append("PLAN_START_DATE=").append(constraints.planStartDate()).append('\n');
        }
        if (constraints.planEndDate() != null) {
            metadata.append("PLAN_END_DATE=").append(constraints.planEndDate()).append('\n');
        }
        if (!constraints.dateLabels().isEmpty()) {
            metadata.append("PLAN_DATE_LABELS:\n");
            constraints.dateLabels().forEach(label -> metadata.append(label).append('\n'));
        }
        if (constraints.trainingFrequency() != null) {
            metadata.append("TRAINING_FREQUENCY_PER_WEEK=")
                    .append(constraints.trainingFrequency())
                    .append('\n');
        }
        if (constraints.trainingDurationMinutes() != null) {
            metadata.append("TRAINING_DURATION_MINUTES=")
                    .append(constraints.trainingDurationMinutes())
                    .append('\n');
        }
        if (constraints.weatherObservedThrough() != null) {
            metadata.append("WEATHER_OBSERVED_THROUGH=")
                    .append(constraints.weatherObservedThrough())
                    .append('\n');
        }
        if (constraints.weatherUnqueriedFrom() != null) {
            metadata.append("WEATHER_UNQUERIED_FROM=")
                    .append(constraints.weatherUnqueriedFrom())
                    .append('\n')
                    .append("从 ").append(formatDateLabel(constraints.weatherUnqueriedFrom()))
                    .append(" 起至计划结束均未获取实时天气，必须明确标注并采用天气无关或室内方案。\n");
        }
    }

    private void appendNames(StringBuilder target, List<String> names) {
        if (names.isEmpty()) {
            target.append("NONE\n");
            return;
        }
        names.forEach(name -> target.append(name).append('\n'));
    }

    private Set<AgentAction> completedCapabilities(AgentPlan plan, AgentStateView state) {
        EnumSet<AgentAction> completed = EnumSet.noneOf(AgentAction.class);
        for (AgentStep step : plan.steps()) {
            if (!CAPABILITY_ORDER.contains(step.action())
                    || state.statusOf(step.id()) != AgentStepStatus.COMPLETED) {
                continue;
            }
            if (state.findObservation(step.id()).map(AgentObservation::success).orElse(false)) {
                completed.add(step.action());
            }
        }
        return Set.copyOf(completed);
    }

    private String weatherScope(AgentPlan plan, AgentStateView state) {
        for (AgentStep step : plan.steps()) {
            if (step.action() != AgentAction.GET_WEATHER
                    || state.statusOf(step.id()) != AgentStepStatus.COMPLETED) {
                continue;
            }
            AgentObservation observation = state.findObservation(step.id()).orElse(null);
            if (observation != null && observation.success()) {
                String period = observation.structuredData().get("period");
                if (period != null && !period.isBlank()) {
                    return sanitize(period);
                }
            }
        }
        return "NOT_AVAILABLE";
    }

    private SynthesisConstraints constraints(
            String originalGoal,
            AgentPlan plan,
            AgentStateView state
    ) {
        Integer frequency = boundedNumber(originalGoal, TRAINING_FREQUENCY, 1, 14);
        Integer duration = boundedNumber(originalGoal, TRAINING_DURATION, 1, 1_440);
        Integer planDays = planDays(originalGoal);
        LocalDate startDate = firstObservedDate(plan, state, AgentAction.GET_DATETIME);
        LocalDate endDate = startDate != null && planDays != null
                ? startDate.plusDays(planDays - 1L)
                : null;
        List<String> dateLabels = dateLabels(startDate, endDate);
        LocalDate observedThrough = lastObservedWeatherDate(plan, state);
        if (observedThrough == null
                && startDate != null
                && "THREE_DAYS".equals(weatherScope(plan, state))) {
            observedThrough = startDate.plusDays(2);
        }
        LocalDate unqueriedFrom = observedThrough != null
                && endDate != null
                && observedThrough.isBefore(endDate)
                ? observedThrough.plusDays(1)
                : null;
        return new SynthesisConstraints(
                startDate,
                endDate,
                dateLabels,
                frequency,
                duration,
                observedThrough,
                unqueriedFrom
        );
    }

    private Integer boundedNumber(String source, Pattern pattern, int minimum, int maximum) {
        Matcher matcher = pattern.matcher(source);
        if (!matcher.find()) {
            return null;
        }
        int value = Integer.parseInt(matcher.group(1));
        return value >= minimum && value <= maximum ? value : null;
    }

    private Integer planDays(String originalGoal) {
        Matcher matcher = PLAN_DAYS.matcher(originalGoal);
        if (!matcher.find()) {
            return null;
        }
        String value = matcher.group(1);
        int days = "七".equals(value) ? 7 : Integer.parseInt(value);
        return days >= 1 && days <= 31 ? days : null;
    }

    private LocalDate firstObservedDate(
            AgentPlan plan,
            AgentStateView state,
            AgentAction action
    ) {
        for (AgentStep step : plan.steps()) {
            if (step.action() != action
                    || state.statusOf(step.id()) != AgentStepStatus.COMPLETED) {
                continue;
            }
            AgentObservation observation = state.findObservation(step.id()).orElse(null);
            if (observation != null && observation.success()) {
                LocalDate date = firstIsoDate(observationText(observation));
                if (date != null) {
                    return date;
                }
            }
        }
        return null;
    }

    private LocalDate lastObservedWeatherDate(AgentPlan plan, AgentStateView state) {
        LinkedHashSet<LocalDate> dates = new LinkedHashSet<>();
        for (AgentStep step : plan.steps()) {
            if (step.action() != AgentAction.GET_WEATHER
                    || state.statusOf(step.id()) != AgentStepStatus.COMPLETED) {
                continue;
            }
            AgentObservation observation = state.findObservation(step.id()).orElse(null);
            if (observation != null && observation.success()) {
                dates.addAll(isoDates(observationText(observation)));
            }
        }
        return dates.stream().max(LocalDate::compareTo).orElse(null);
    }

    private String observationText(AgentObservation observation) {
        String modelContent = observation.structuredData().get("modelContent");
        return modelContent == null || modelContent.isBlank() ? observation.summary() : modelContent;
    }

    private LocalDate firstIsoDate(String source) {
        List<LocalDate> dates = isoDates(source);
        return dates.isEmpty() ? null : dates.getFirst();
    }

    private List<LocalDate> isoDates(String source) {
        List<LocalDate> dates = new ArrayList<>();
        if (source == null || source.isBlank()) {
            return dates;
        }
        Matcher matcher = ISO_DATE.matcher(source);
        while (matcher.find()) {
            try {
                dates.add(LocalDate.parse(matcher.group(1)));
            } catch (DateTimeParseException ignored) {
                // Ignore malformed date-like values in an otherwise successful observation.
            }
        }
        return dates;
    }

    private List<String> dateLabels(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null || endDate.isBefore(startDate)) {
            return List.of();
        }
        List<String> labels = new ArrayList<>();
        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            labels.add(formatDateLabel(date));
        }
        return List.copyOf(labels);
    }

    private String formatDateLabel(LocalDate date) {
        String weekday = switch (date.getDayOfWeek()) {
            case MONDAY -> "周一";
            case TUESDAY -> "周二";
            case WEDNESDAY -> "周三";
            case THURSDAY -> "周四";
            case FRIDAY -> "周五";
            case SATURDAY -> "周六";
            case SUNDAY -> "周日";
        };
        return date.getMonthValue() + "月" + date.getDayOfMonth() + "日（" + weekday + "）";
    }

    private String observationBlock(AgentStep step, AgentObservation observation) {
        if (step.action() == AgentAction.GET_WEATHER) {
            return weatherObservationBlock(observation);
        }
        StringBuilder block = new StringBuilder();
        block.append("- ").append(label(step.action())).append("：")
                .append(sanitize(observation.summary())).append('\n');
        observation.structuredData().entrySet().stream()
                .filter(entry -> isSafeKey(entry.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    String value = sanitize(entry.getValue());
                    if (!value.isBlank()) {
                        block.append("  ").append(entry.getKey()).append("：")
                                .append(value).append('\n');
                    }
                });
        return block.toString();
    }

    private String weatherObservationBlock(AgentObservation observation) {
        String period = sanitize(observation.structuredData().get("period"));
        String location = sanitize(observation.structuredData().get("location"));
        String realData = sanitize(observation.structuredData().get("modelContent"));
        if (realData.isBlank()) {
            realData = sanitize(observation.summary());
        }
        return "[真实天气观测]\n"
                + "查询地点：" + (location.isBlank() ? "未标明" : location) + "\n"
                + "查询范围：" + (period.isBlank() ? "NOT_AVAILABLE" : period) + "\n"
                + "天气事实只覆盖上述查询范围。超出范围的日期没有实时天气数据，"
                + "不得推断为晴、雨、温度或其他具体天气。\n"
                + "若真实数据中某日为雨、中雨、大雨、雷阵雨、雪等不适合户外的天气，"
                + "该日在所有运动章节都必须改为室内步行、自重训练、健身房等室内等价方案；"
                + "除非明确写成确认无雨后的条件式备选，不得再标为户外。\n"
                + "以下仅为工具真实返回的数据：\n"
                + realData + "\n";
    }

    private boolean isSafeKey(String key) {
        return key != null
                && !SENSITIVE_KEY.matcher(key).matches()
                && !BINARY_KEY.matcher(key).matches()
                && !INTERNAL_KEY.matcher(key).matches();
    }

    private String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        StringBuilder clean = new StringBuilder();
        for (String line : raw.replace('\u0000', ' ').lines().toList()) {
            String stripped = line.strip();
            if (stripped.startsWith("at ")
                    || stripped.startsWith("Caused by:")
                    || stripped.startsWith("Suppressed:")) {
                continue;
            }
            if (!clean.isEmpty()) {
                clean.append(' ');
            }
            clean.append(stripped);
        }
        String redacted = INLINE_SECRET.matcher(clean).replaceAll("$1=[REDACTED]");
        if (redacted.startsWith("data:") || redacted.contains(";base64,")) {
            return "[已省略二进制内容]";
        }
        return redacted;
    }

    private String label(AgentAction action) {
        return switch (action) {
            case GET_DATETIME -> "日期时间";
            case GET_WEATHER -> "天气";
            case RETRIEVE_KNOWLEDGE -> "本地知识检索";
            case RUN_EXERCISE_SKILL -> "运动建议";
            case RUN_MEAL_SKILL -> "饮食建议";
            case CALCULATE -> "计算结果";
            case CREATE_TODO -> "待办结果";
            case VALIDATE -> "一致性校验";
            case SYNTHESIZE -> "最终汇总";
        };
    }

    private void appendWithinTotal(StringBuilder target, String text) {
        int remaining = MAX_TOTAL_CHARS - target.length();
        if (remaining > 0) {
            target.append(safeTruncate(text, remaining));
        }
    }

    private String safeTruncate(String value, int maximum) {
        if (value.length() <= maximum) {
            return value;
        }
        int end = maximum;
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }

    private record SynthesisConstraints(
            LocalDate planStartDate,
            LocalDate planEndDate,
            List<String> dateLabels,
            Integer trainingFrequency,
            Integer trainingDurationMinutes,
            LocalDate weatherObservedThrough,
            LocalDate weatherUnqueriedFrom
    ) {
        private SynthesisConstraints {
            dateLabels = List.copyOf(dateLabels);
        }
    }
}
