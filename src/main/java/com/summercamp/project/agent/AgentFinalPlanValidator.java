package com.summercamp.project.agent;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Application-controlled consistency gate for the user-visible synthesis answer. */
@Component
public final class AgentFinalPlanValidator {
    private static final Pattern START_DATE = metadataDate("PLAN_START_DATE");
    private static final Pattern END_DATE = metadataDate("PLAN_END_DATE");
    private static final Pattern WEATHER_UNQUERIED_FROM = metadataDate("WEATHER_UNQUERIED_FROM");
    private static final Pattern TRAINING_FREQUENCY = metadataInteger(
            "TRAINING_FREQUENCY_PER_WEEK");
    private static final Pattern TRAINING_DURATION = metadataInteger(
            "TRAINING_SESSION_TOTAL_MINUTES");
    private static final Pattern WEEKDAY = Pattern.compile("(?:周|星期)([一二三四五六日天])");
    private static final Pattern NUMERIC_TEMPERATURE = Pattern.compile(
            "(?<!\\d)-?\\d{1,3}(?:\\.\\d+)?\\s*(?:℃|°\\s*C)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONCRETE_WIND = Pattern.compile(
            "(?:东北|东南|西北|西南|东|南|西|北)风|"
                    + "风力\\s*[:：]?\\s*\\d|(?<!\\d)\\d{1,2}\\s*级");
    private static final Pattern CONCRETE_CONDITION = Pattern.compile(
            "晴(?:朗|天)?|多云|阴(?:天)?|小雨|中雨|大雨|暴雨|阵雨|雷雨|雨夹雪|小雪|中雪|大雪|暴雪");
    private static final Pattern CONDITIONAL_OR_UNKNOWN = Pattern.compile(
            "如|若|如果|视天气|天气允许|当天查看|当日查看|"
                    + "未获取|未查询|未知|不确定|以实时天气为准");
    private static final int MAX_DATE_SECTION_CHARS = 500;
    private static final int MAX_WEEKDAY_DISTANCE_CHARS = 12;

    public FinalPlanValidationResult validate(
            String originalGoal,
            AgentPlan plan,
            AgentStateView state,
            String synthesisContext,
            String answer
    ) {
        if (answer == null || answer.isBlank()) {
            return FinalPlanValidationResult.invalid(List.of(
                    FinalPlanValidationIssueCode.MISSING_PLAN_DATE));
        }
        return validate(
                originalGoal,
                plan,
                state,
                synthesisContext,
                AgentSynthesisResult.answerOnly(answer));
    }

    public FinalPlanValidationResult validate(
            String originalGoal,
            AgentPlan plan,
            AgentStateView state,
            String synthesisContext,
            AgentSynthesisResult synthesisResult
    ) {
        if (originalGoal == null || originalGoal.isBlank()) {
            throw new IllegalArgumentException("originalGoal must not be blank");
        }
        Objects.requireNonNull(plan, "plan must not be null");
        Objects.requireNonNull(state, "state must not be null");
        if (synthesisContext == null || synthesisContext.isBlank()) {
            throw new IllegalArgumentException("synthesisContext must not be blank");
        }
        Objects.requireNonNull(synthesisResult, "synthesisResult must not be null");
        LocalDate start = readMetadataDate(synthesisContext, START_DATE);
        LocalDate end = readMetadataDate(synthesisContext, END_DATE);
        Integer trainingFrequency = readMetadataInteger(
                synthesisContext, TRAINING_FREQUENCY);
        Integer trainingDuration = readMetadataInteger(
                synthesisContext, TRAINING_DURATION);
        if (!synthesisResult.parsed()) {
            return invalidSynthesisResult(
                    synthesisResult, trainingFrequency, trainingDuration);
        }

        String answer = synthesisResult.answer();
        List<LocalDate> requiredDates = start != null && end != null && !end.isBefore(start)
                ? datesBetween(start, end)
                : List.of();
        List<DateOccurrence> occurrences = findOccurrences(answer, requiredDates);
        EnumSet<FinalPlanValidationIssueCode> issues = EnumSet.noneOf(
                FinalPlanValidationIssueCode.class);

        for (LocalDate date : requiredDates) {
            if (occurrences.stream().noneMatch(occurrence -> occurrence.date().equals(date))) {
                issues.add(FinalPlanValidationIssueCode.MISSING_PLAN_DATE);
            }
        }
        if (hasWrongWeekday(answer, occurrences)) {
            issues.add(FinalPlanValidationIssueCode.WRONG_WEEKDAY);
        }

        LocalDate unqueriedFrom = readMetadataDate(synthesisContext, WEATHER_UNQUERIED_FROM);
        if (unqueriedFrom != null
                && hasConcreteWeatherOutsideScope(answer, occurrences, unqueriedFrom)) {
            issues.add(FinalPlanValidationIssueCode.CONCRETE_WEATHER_OUTSIDE_SCOPE);
        }
        validateTrainingAudit(
                synthesisResult.audit(),
                answer,
                start,
                end,
                trainingFrequency,
                trainingDuration,
                issues);

        return issues.isEmpty()
                ? FinalPlanValidationResult.validResult()
                : FinalPlanValidationResult.invalid(List.copyOf(issues));
    }

    private FinalPlanValidationResult invalidSynthesisResult(
            AgentSynthesisResult synthesisResult,
            Integer trainingFrequency,
            Integer trainingDuration
    ) {
        AgentSynthesisParseError parseError = synthesisResult.parseError().orElseThrow();
        FinalPlanValidationIssueCode code = switch (parseError) {
            case AUDIT_MISSING -> trainingFrequency != null || trainingDuration != null
                    ? FinalPlanValidationIssueCode.TRAINING_AUDIT_MISSING
                    : FinalPlanValidationIssueCode.SYNTHESIS_ENVELOPE_INVALID;
            case DUPLICATE_TRAINING_DATE ->
                    FinalPlanValidationIssueCode.TRAINING_FREQUENCY_MISMATCH;
            case MALFORMED_JSON, INVALID_SCHEMA ->
                    FinalPlanValidationIssueCode.SYNTHESIS_ENVELOPE_INVALID;
        };
        return FinalPlanValidationResult.invalid(List.of(code));
    }

    private void validateTrainingAudit(
            AgentTrainingAudit audit,
            String answer,
            LocalDate start,
            LocalDate end,
            Integer requiredFrequency,
            Integer durationLimit,
            EnumSet<FinalPlanValidationIssueCode> issues
    ) {
        List<LocalDate> trainingDates = audit.trainingDates();
        Set<LocalDate> uniqueDates = new HashSet<>(trainingDates);
        if (requiredFrequency != null) {
            if (!audit.trainingDatesPresent()) {
                issues.add(FinalPlanValidationIssueCode.TRAINING_AUDIT_MISSING);
            } else if (trainingDates.size() != requiredFrequency
                    || uniqueDates.size() != trainingDates.size()) {
                issues.add(FinalPlanValidationIssueCode.TRAINING_FREQUENCY_MISMATCH);
            }
        } else if (uniqueDates.size() != trainingDates.size()) {
            issues.add(FinalPlanValidationIssueCode.TRAINING_FREQUENCY_MISMATCH);
        }

        if (audit.trainingDatesPresent()) {
            if (start != null && end != null && trainingDates.stream()
                    .anyMatch(date -> date.isBefore(start) || date.isAfter(end))) {
                issues.add(FinalPlanValidationIssueCode.TRAINING_DATE_OUTSIDE_PLAN);
            }
            if (trainingDates.stream().anyMatch(date -> !datePattern(date).matcher(answer).find())) {
                issues.add(FinalPlanValidationIssueCode.TRAINING_DATE_NOT_PRESENT_IN_ANSWER);
            }
        }

        if (durationLimit == null) {
            return;
        }
        if (!audit.sessionDurationsPresent()) {
            issues.add(FinalPlanValidationIssueCode.TRAINING_DURATION_MISSING);
            return;
        }
        Map<LocalDate, Integer> durations = audit.sessionDurationMinutesByDate();
        if (audit.trainingDatesPresent()) {
            if (trainingDates.stream().anyMatch(date -> !durations.containsKey(date))) {
                issues.add(FinalPlanValidationIssueCode.TRAINING_DURATION_MISSING);
            }
            if (durations.keySet().stream().anyMatch(date -> !uniqueDates.contains(date))) {
                issues.add(FinalPlanValidationIssueCode.TRAINING_DURATION_DATE_MISMATCH);
            }
        }
        if (durations.values().stream().anyMatch(value -> value > durationLimit)) {
            issues.add(FinalPlanValidationIssueCode.TRAINING_DURATION_EXCEEDED);
        }
    }

    private boolean hasWrongWeekday(String answer, List<DateOccurrence> occurrences) {
        for (DateOccurrence occurrence : occurrences) {
            int suffixEnd = Math.min(answer.length(), occurrence.end() + MAX_WEEKDAY_DISTANCE_CHARS);
            Matcher weekday = WEEKDAY.matcher(answer.substring(occurrence.end(), suffixEnd));
            if (weekday.find() && !weekday.group(1).equals(weekday(occurrence.date()))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasConcreteWeatherOutsideScope(
            String answer,
            List<DateOccurrence> occurrences,
            LocalDate unqueriedFrom
    ) {
        List<DateOccurrence> ordered = occurrences.stream()
                .sorted(Comparator.comparingInt(DateOccurrence::start))
                .toList();
        for (int index = 0; index < ordered.size(); index++) {
            DateOccurrence occurrence = ordered.get(index);
            if (occurrence.date().isBefore(unqueriedFrom)) {
                continue;
            }
            int nextDateStart = index + 1 < ordered.size()
                    ? ordered.get(index + 1).start()
                    : answer.length();
            int sectionEnd = Math.min(
                    nextDateStart,
                    Math.min(answer.length(), occurrence.end() + MAX_DATE_SECTION_CHARS));
            if (containsConcreteWeather(answer.substring(occurrence.end(), sectionEnd))) {
                return true;
            }
        }
        return false;
    }

    private boolean containsConcreteWeather(String section) {
        for (String clause : section.split("[\\n。；;]")) {
            if (NUMERIC_TEMPERATURE.matcher(clause).find()
                    || CONCRETE_WIND.matcher(clause).find()) {
                return true;
            }
            if (CONCRETE_CONDITION.matcher(clause).find()
                    && !CONDITIONAL_OR_UNKNOWN.matcher(clause).find()) {
                return true;
            }
        }
        return false;
    }

    private List<DateOccurrence> findOccurrences(String answer, List<LocalDate> dates) {
        List<DateOccurrence> occurrences = new ArrayList<>();
        for (LocalDate date : dates) {
            Matcher matcher = datePattern(date).matcher(answer);
            while (matcher.find()) {
                occurrences.add(new DateOccurrence(date, matcher.start(), matcher.end()));
            }
        }
        return occurrences.stream()
                .sorted(Comparator.comparingInt(DateOccurrence::start))
                .toList();
    }

    private Pattern datePattern(LocalDate date) {
        String year = Integer.toString(date.getYear());
        String month = "0?" + date.getMonthValue();
        String day = "0?" + date.getDayOfMonth();
        String expression = "(?<![\\d/])(?:"
                + year + "\\s*[-/]\\s*" + month + "\\s*[-/]\\s*" + day
                + "|" + year + "年\\s*" + month + "月\\s*" + day + "日?"
                + "|" + month + "月\\s*" + day + "日?"
                + "|" + month + "/" + day
                + ")(?![\\d/])";
        return Pattern.compile(expression);
    }

    private List<LocalDate> datesBetween(LocalDate start, LocalDate end) {
        List<LocalDate> dates = new ArrayList<>();
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            dates.add(date);
        }
        return List.copyOf(dates);
    }

    private LocalDate readMetadataDate(String context, Pattern pattern) {
        Matcher matcher = pattern.matcher(context);
        if (!matcher.find()) {
            return null;
        }
        try {
            return LocalDate.parse(matcher.group(1));
        } catch (DateTimeException ignored) {
            return null;
        }
    }

    private Integer readMetadataInteger(String context, Pattern pattern) {
        Matcher matcher = pattern.matcher(context);
        if (!matcher.find()) {
            return null;
        }
        try {
            int value = Integer.parseInt(matcher.group(1));
            return value > 0 ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String weekday(LocalDate date) {
        return switch (date.getDayOfWeek()) {
            case MONDAY -> "一";
            case TUESDAY -> "二";
            case WEDNESDAY -> "三";
            case THURSDAY -> "四";
            case FRIDAY -> "五";
            case SATURDAY -> "六";
            case SUNDAY -> "日";
        };
    }

    private static Pattern metadataDate(String key) {
        return Pattern.compile("(?m)^" + Pattern.quote(key) + "=(20\\d{2}-\\d{2}-\\d{2})$");
    }

    private static Pattern metadataInteger(String key) {
        return Pattern.compile("(?m)^" + Pattern.quote(key) + "=(\\d{1,4})$");
    }

    private record DateOccurrence(LocalDate date, int start, int end) {
    }
}
