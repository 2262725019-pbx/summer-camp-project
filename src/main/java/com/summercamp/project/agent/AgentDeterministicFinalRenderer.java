package com.summercamp.project.agent;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Typed, provider-independent renderer over already completed and validated observations. */
@Component
public final class AgentDeterministicFinalRenderer {
    private static final Pattern METADATA_DATE = Pattern.compile(
            "(?m)^%s=(20\\d{2}-\\d{2}-\\d{2})$");
    private static final Pattern METADATA_INTEGER = Pattern.compile(
            "(?m)^%s=(\\d{1,4})$");
    private static final Pattern DATE_LABEL = Pattern.compile(
            "^(\\d{1,2})月(\\d{1,2})日（(周[一二三四五六日])）$");
    private static final Pattern METADATA_BOUNDARY = Pattern.compile("^[A-Z_]+(?:=|:).*$");
    private static final int MAX_REFERENCE_CHARS = 1_500;
    private final GoalRequirementExtractor requirementExtractor = new GoalRequirementExtractor();

    public AgentSynthesisResult render(
            String originalGoal,
            AgentPlan plan,
            AgentStateView state,
            String synthesisContext
    ) {
        java.util.Set<GoalRequirement> requirements = requirementExtractor.extract(originalGoal);
        boolean explicitHealthPlanning = originalGoal != null && originalGoal.contains("健康生活");
        if (!explicitHealthPlanning
                && !requirements.contains(GoalRequirement.EXERCISE)
                && !requirements.contains(GoalRequirement.MEAL)
                && !requirements.contains(GoalRequirement.LIFESTYLE)) {
            throw new IllegalStateException(
                    "Deterministic synthesis is limited to health-planning goals");
        }
        LocalDate start = date(synthesisContext, "PLAN_START_DATE");
        LocalDate end = date(synthesisContext, "PLAN_END_DATE");
        if (start == null || end == null || end.isBefore(start)) {
            throw new IllegalStateException("Deterministic synthesis requires a valid plan horizon");
        }
        List<DatedLabel> schedule = dateLabels(synthesisContext, start, end);
        if (schedule.size() != end.toEpochDay() - start.toEpochDay() + 1) {
            throw new IllegalStateException("PLAN_DATE_LABELS do not cover the plan horizon");
        }
        Integer frequency = integer(synthesisContext, "TRAINING_FREQUENCY_PER_WEEK");
        Integer duration = integer(synthesisContext, "TRAINING_SESSION_TOTAL_MINUTES");
        List<LocalDate> trainingDates = frequency == null
                ? List.of()
                : selectTrainingDates(schedule, frequency);
        Map<LocalDate, Integer> durations = new LinkedHashMap<>();
        if (duration != null) {
            for (LocalDate trainingDate : trainingDates) {
                durations.put(trainingDate, duration);
            }
        }
        AgentTrainingAudit audit = new AgentTrainingAudit(
                frequency != null,
                trainingDates,
                duration != null,
                durations);
        LocalDate unqueriedFrom = date(synthesisContext, "WEATHER_UNQUERIED_FROM");

        StringBuilder answer = new StringBuilder("大学生健康生活规划\n\n");
        appendReference(answer, "运动执行参考", observation(plan, state, AgentAction.RUN_EXERCISE_SKILL));
        appendReference(answer, "饮食执行参考", observation(plan, state, AgentAction.RUN_MEAL_SKILL));
        answer.append("每日安排\n");
        for (DatedLabel entry : schedule) {
            boolean training = trainingDates.contains(entry.date());
            answer.append("\n").append(entry.label()).append("\n");
            if (training) {
                if (duration != null) {
                    answer.append("- 正式训练：整次训练总时长")
                            .append(duration)
                            .append("分钟，按运动执行参考完成热身、主体、有氧和拉伸。\n");
                } else {
                    answer.append("- 正式训练：按运动执行参考控制强度和总量。\n");
                }
            } else {
                answer.append("- 活动：恢复日，可安排轻松散步或舒缓拉伸，不计正式训练。\n");
            }
            answer.append("- 饮食：按饮食执行参考安排各餐，保持规律进餐和充足饮水。\n")
                    .append("- 作息：保持固定起床与入睡时间，预留充足睡眠。\n");
            if (unqueriedFrom != null && !entry.date().isBefore(unqueriedFrom)) {
                answer.append("- 天气：未获取实时天气，建议当天查看天气后决定是否户外。\n");
            } else {
                answer.append("- 天气：已获取近期天气，请按已完成的天气结果选择室内或户外。\n");
            }
        }
        answer.append("\n当前模型生成服务繁忙，本计划依据已完成的天气、运动和饮食结果自动整理。");
        return AgentSynthesisResult.parsed(new AgentSynthesisEnvelope(answer.toString(), audit));
    }

    private List<LocalDate> selectTrainingDates(List<DatedLabel> schedule, int frequency) {
        if (frequency < 1 || frequency > schedule.size()) {
            throw new IllegalStateException("Training frequency cannot fit the plan horizon");
        }
        List<LocalDate> dates = new ArrayList<>();
        if (frequency == 1) {
            dates.add(schedule.getFirst().date());
            return List.copyOf(dates);
        }
        for (int index = 0; index < frequency; index++) {
            int position = (int) Math.round(
                    index * (schedule.size() - 1.0) / (frequency - 1.0));
            LocalDate date = schedule.get(position).date();
            if (dates.contains(date)) {
                throw new IllegalStateException("Training distribution is not unique");
            }
            dates.add(date);
        }
        return List.copyOf(dates);
    }

    private List<DatedLabel> dateLabels(String context, LocalDate start, LocalDate end) {
        List<String> lines = context.lines().toList();
        int marker = lines.indexOf("PLAN_DATE_LABELS=");
        if (marker < 0) {
            return List.of();
        }
        List<DatedLabel> labels = new ArrayList<>();
        for (int index = marker + 1; index < lines.size(); index++) {
            String line = lines.get(index).strip();
            if (line.isBlank()) {
                continue;
            }
            if (METADATA_BOUNDARY.matcher(line).matches()) {
                break;
            }
            Matcher matcher = DATE_LABEL.matcher(line);
            if (!matcher.matches()) {
                throw new IllegalStateException("Invalid PLAN_DATE_LABELS entry");
            }
            LocalDate date = LocalDate.of(
                    start.getYear(),
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)));
            if (date.isBefore(start) || date.isAfter(end) || !weekday(date).equals(matcher.group(3))) {
                throw new IllegalStateException("PLAN_DATE_LABELS entry is inconsistent");
            }
            labels.add(new DatedLabel(date, line));
        }
        return List.copyOf(labels);
    }

    private void appendReference(
            StringBuilder answer,
            String title,
            AgentObservation observation
    ) {
        if (observation == null || !observation.success()) {
            return;
        }
        String raw = observation.structuredData().getOrDefault("reply", observation.summary());
        String safe = sanitizeReference(raw);
        if (!safe.isBlank()) {
            answer.append(title).append("：\n").append(safe).append("\n\n");
        }
    }

    private AgentObservation observation(
            AgentPlan plan,
            AgentStateView state,
            AgentAction action
    ) {
        for (AgentStep step : plan.steps()) {
            if (step.action() == action && state.statusOf(step.id()) == AgentStepStatus.COMPLETED) {
                AgentObservation observation = state.findObservation(step.id()).orElse(null);
                if (observation != null && observation.success()) {
                    return observation;
                }
            }
        }
        return null;
    }

    private String sanitizeReference(String raw) {
        String value = raw == null ? "" : raw.replace('\u0000', ' ').strip();
        value = value.replaceAll("(?<!\\d)20\\d{2}-\\d{2}-\\d{2}(?!\\d)", "[日期见每日安排]")
                .replaceAll("(?<!\\d)\\d{1,2}月\\d{1,2}日(?!\\d)", "[日期见每日安排]");
        if (value.length() <= MAX_REFERENCE_CHARS) {
            return value;
        }
        return value.substring(0, MAX_REFERENCE_CHARS);
    }

    private LocalDate date(String context, String key) {
        Matcher matcher = Pattern.compile(METADATA_DATE.pattern().formatted(Pattern.quote(key)))
                .matcher(context);
        if (!matcher.find()) {
            return null;
        }
        try {
            return LocalDate.parse(matcher.group(1));
        } catch (DateTimeException ignored) {
            return null;
        }
    }

    private Integer integer(String context, String key) {
        Matcher matcher = Pattern.compile(METADATA_INTEGER.pattern().formatted(Pattern.quote(key)))
                .matcher(context);
        if (!matcher.find()) {
            return null;
        }
        int value = Integer.parseInt(matcher.group(1));
        return value > 0 ? value : null;
    }

    private String weekday(LocalDate date) {
        return switch (date.getDayOfWeek()) {
            case MONDAY -> "周一";
            case TUESDAY -> "周二";
            case WEDNESDAY -> "周三";
            case THURSDAY -> "周四";
            case FRIDAY -> "周五";
            case SATURDAY -> "周六";
            case SUNDAY -> "周日";
        };
    }

    private record DatedLabel(LocalDate date, String label) {
    }
}
