package com.summercamp.project.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public final class AgentSynthesisContextBuilder {
    public static final int MAX_TOTAL_CHARS = 20_000;
    public static final int MAX_SYNTHESIS_RAG_CHARS = 800;

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
    private static final Pattern INLINE_SECRET = Pattern.compile(
            "(?i)[\"']?(api.?key|authorization|password|secret|access.?token|private.?key)"
                    + "[\"']?\\s*[:=]\\s*(?:[\"'][^\"']*[\"']"
                    + "|(?:(?:Bearer|Basic)\\s+)?[^\\s,;}]+)"
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
    private final ObjectMapper objectMapper = new ObjectMapper();

    public String build(String originalGoal, AgentPlan plan, AgentStateView state) {
        return buildDetailed(originalGoal, plan, state).context();
    }

    public BuiltContext buildDetailed(
            String originalGoal,
            AgentPlan plan,
            AgentStateView state
    ) {
        if (originalGoal == null || originalGoal.isBlank()) {
            throw new IllegalArgumentException("originalGoal must not be blank");
        }
        if (plan == null || state == null) {
            throw new IllegalArgumentException("plan and state must not be null");
        }

        Set<GoalRequirement> requiredDomains = requirementExtractor.extract(originalGoal);
        Set<AgentAction> completedCapabilities = completedCapabilities(plan, state);
        SynthesisConstraints constraints = constraints(originalGoal, plan, state);
        List<ContextBlock> blocks = new ArrayList<>();
        blocks.add(new ContextBlock(BlockType.METADATA, groundingMetadata(
                requiredDomains,
                completedCapabilities,
                weatherScope(plan, state),
                constraints
        ) + "\nFACT_BLOCKS:\n"));
        blocks.add(new ContextBlock(
                BlockType.ORIGINAL_GOAL,
                "ORIGINAL_GOAL:\n" + sanitize(originalGoal) + "\n"));
        for (AgentStep step : plan.steps()) {
            if (!INCLUDED_ACTIONS.contains(step.action())
                    || state.statusOf(step.id()) != AgentStepStatus.COMPLETED) {
                continue;
            }
            AgentObservation observation = state.findObservation(step.id()).orElse(null);
            if (observation == null || !observation.success()) {
                continue;
            }
            blocks.add(observationBlock(step, observation, constraints));
        }
        StringBuilder result = new StringBuilder();
        MutableBreakdown breakdown = new MutableBreakdown();
        for (ContextBlock block : blocks) {
            if (block.text().isBlank()) {
                continue;
            }
            result.append(block.text());
            breakdown.add(block.type(), block.text().length());
        }
        if (result.length() > MAX_TOTAL_CHARS) {
            throw new IllegalStateException(
                    "Grounded synthesis context exceeds hard safety limit");
        }
        return new BuiltContext(result.toString(), breakdown.snapshot(result.length()));
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
        metadata.append("WEATHER_SCOPE=")
                .append(weatherScope)
                .append('\n');
        appendConstraintMetadata(metadata, constraints);
        metadata.append("FACT_SOURCE_PRIORITY:\n")
                .append("WEATHER=GET_WEATHER\n")
                .append("EXERCISE=RUN_EXERCISE_SKILL\n")
                .append("MEAL=RUN_MEAL_SKILL\n")
                .append("CALCULATION=CALCULATE\n")
                .append("VALIDATED_FACTS_ONLY=true\n");
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
            metadata.append("PLAN_DATE_LABELS=\n");
            constraints.dateLabels().forEach(label -> metadata.append(label).append('\n'));
        }
        if (constraints.trainingFrequency() != null) {
            metadata.append("TRAINING_FREQUENCY_PER_WEEK=")
                    .append(constraints.trainingFrequency())
                    .append('\n');
        }
        if (constraints.trainingDurationMinutes() != null) {
            metadata.append("TRAINING_SESSION_TOTAL_MINUTES=")
                    .append(constraints.trainingDurationMinutes())
                    .append('\n')
                    .append("TRAINING_SESSION_TOTAL_RULE=热身+主训练+有氧+拉伸合计必须<=")
                    .append(constraints.trainingDurationMinutes())
                    .append("分钟。\n");
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
                    .append("WEATHER_UNQUERIED_RULE=从 WEATHER_UNQUERIED_FROM 起，任何具体晴雨、"
                            + "温度、风力都属于未知，不得生成或推断。\n");
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

    private ContextBlock observationBlock(
            AgentStep step,
            AgentObservation observation,
            SynthesisConstraints constraints
    ) {
        return switch (step.action()) {
            case GET_DATETIME -> new ContextBlock(
                    BlockType.DATETIME,
                    compactGenericBlock("DATETIME", observation));
            case GET_WEATHER -> new ContextBlock(
                    BlockType.WEATHER,
                    weatherObservationBlock(observation, constraints));
            case RETRIEVE_KNOWLEDGE -> new ContextBlock(
                    BlockType.RAG,
                    ragObservationBlock(observation));
            case RUN_EXERCISE_SKILL -> new ContextBlock(
                    BlockType.EXERCISE,
                    skillObservationBlock("EXERCISE_SKILL_RESULT", observation));
            case RUN_MEAL_SKILL -> new ContextBlock(
                    BlockType.MEAL,
                    skillObservationBlock("MEAL_SKILL_RESULT", observation));
            case CALCULATE -> new ContextBlock(
                    BlockType.CALCULATE,
                    compactGenericBlock("CALCULATION", observation));
            case CREATE_TODO -> new ContextBlock(
                    BlockType.TODO,
                    compactGenericBlock("TODO", observation));
            case VALIDATE -> new ContextBlock(BlockType.VALIDATE, "VALIDATE=PASS\n");
            case SYNTHESIZE -> throw new IllegalArgumentException(
                    "Synthesis observations cannot be synthesis inputs");
        };
    }

    private String weatherObservationBlock(
            AgentObservation observation,
            SynthesisConstraints constraints
    ) {
        JsonNode result = providerResult(observation.structuredData().get("modelContent"));
        JsonNode data = result.path("data");
        String location = firstNonBlank(
                sanitize(observation.structuredData().get("location")),
                sanitize(data.path("location").asText()));
        String period = firstNonBlank(
                sanitize(observation.structuredData().get("period")),
                sanitize(data.path("period").asText()));
        StringBuilder block = new StringBuilder("WEATHER:\n")
                .append("location=").append(location.isBlank() ? "NOT_AVAILABLE" : location).append('\n')
                .append("scope=").append(period.isBlank() ? "NOT_AVAILABLE" : period).append('\n');
        String reportTime = sanitize(data.path("reportTime").asText());
        if (!reportTime.isBlank()) {
            block.append("reportTime=").append(reportTime).append('\n');
        }
        JsonNode current = data.path("current");
        if (current.isObject()) {
            block.append("CURRENT: ")
                    .append(sanitize(current.path("weather").asText()))
                    .append(", ").append(sanitize(current.path("temperature").asText())).append("℃")
                    .append(", humidity=").append(sanitize(current.path("humidity").asText())).append('%')
                    .append(", wind=").append(sanitize(current.path("windDirection").asText()))
                    .append(sanitize(current.path("windPower").asText())).append("级\n");
        }
        JsonNode forecasts = data.path("forecasts");
        if (forecasts.isArray()) {
            for (JsonNode day : forecasts) {
                block.append(sanitize(day.path("date").asText())).append(": day=")
                        .append(sanitize(day.path("dayWeather").asText())).append(' ')
                        .append(sanitize(day.path("dayTemperature").asText())).append("℃, night=")
                        .append(sanitize(day.path("nightWeather").asText())).append(' ')
                        .append(sanitize(day.path("nightTemperature").asText())).append("℃, wind=")
                        .append(sanitize(day.path("dayWind").asText()))
                        .append(sanitize(day.path("dayPower").asText())).append("级\n");
            }
        }
        if (!current.isObject() && !forecasts.isArray()) {
            String formatted = sanitize(result.path("formatted_text").asText());
            String fallback = formatted.isBlank()
                    ? compactProviderResult(observation)
                    : formatted;
            if (fallback.isBlank()) {
                fallback = sanitize(observation.summary());
            }
            if (!fallback.isBlank()) {
                block.append("facts=").append(fallback).append('\n');
            }
        }
        if (constraints.weatherUnqueriedFrom() != null) {
            block.append("UNQUERIED_FROM=")
                    .append(constraints.weatherUnqueriedFrom())
                    .append('\n');
        }
        return block.toString();
    }

    private String skillObservationBlock(String label, AgentObservation observation) {
        String reply = sanitize(observation.structuredData().get("reply"));
        String content = reply.isBlank() ? sanitize(observation.summary()) : reply;
        return label + ":\n" + content + "\n";
    }

    private String ragObservationBlock(AgentObservation observation) {
        boolean matched = Boolean.parseBoolean(observation.structuredData().getOrDefault(
                "matched", "false"));
        if (!matched) {
            return "RAG_MATCHED=false\n";
        }
        String evidence = sanitize(observation.structuredData().get("promptContext"));
        String prefix = "RAG_MATCHED=true\nRAG_EVIDENCE:\n";
        int evidenceBudget = MAX_SYNTHESIS_RAG_CHARS - prefix.length() - 1;
        return prefix + truncateRagEvidence(evidence, evidenceBudget) + "\n";
    }

    private String compactGenericBlock(String label, AgentObservation observation) {
        String content = compactProviderResult(observation);
        if (content.isBlank()) {
            content = sanitize(observation.summary());
        }
        return label + ":\n" + content + "\n";
    }

    private String compactProviderResult(AgentObservation observation) {
        JsonNode result = providerResult(observation.structuredData().get("modelContent"));
        if (result.isTextual()) {
            return sanitize(result.asText());
        }
        if (!result.isMissingNode() && !result.isNull()) {
            return sanitize(result.toString());
        }
        return "";
    }

    private JsonNode providerResult(String raw) {
        if (raw == null || raw.isBlank()) {
            return objectMapper.missingNode();
        }
        try {
            JsonNode parsed = objectMapper.readTree(raw);
            if (parsed != null && parsed.path("success").asBoolean(false) && parsed.has("result")) {
                return parsed.path("result");
            }
            return parsed == null ? objectMapper.missingNode() : parsed;
        } catch (JsonProcessingException exception) {
            return objectMapper.getNodeFactory().textNode(sanitize(raw));
        }
    }

    private String truncateRagEvidence(String evidence, int maximum) {
        if (evidence.length() <= maximum) {
            return evidence;
        }
        int markerLength = "\n[其余 RAG evidence 已按预算省略]".length();
        return safeTruncate(evidence, maximum - markerLength)
                + "\n[其余 RAG evidence 已按预算省略]";
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
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
        String redacted = INLINE_SECRET.matcher(clean).replaceAll("[REDACTED]");
        if (redacted.startsWith("data:") || redacted.contains(";base64,")) {
            return "[已省略二进制内容]";
        }
        return redacted;
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

    public record BuiltContext(String context, Breakdown breakdown) {
        public BuiltContext {
            context = context == null ? "" : context;
            if (breakdown == null || breakdown.totalChars() != context.length()) {
                throw new IllegalArgumentException("breakdown must explain the complete context");
            }
        }
    }

    public record Breakdown(
            long metadataChars,
            long originalGoalChars,
            long datetimeChars,
            long weatherChars,
            long exerciseChars,
            long mealChars,
            long ragChars,
            long todoChars,
            long validateChars,
            long calculateChars,
            long totalChars
    ) {
        public long explainedChars() {
            return metadataChars
                    + originalGoalChars
                    + datetimeChars
                    + weatherChars
                    + exerciseChars
                    + mealChars
                    + ragChars
                    + todoChars
                    + validateChars
                    + calculateChars;
        }
    }

    private record ContextBlock(BlockType type, String text) {
        private ContextBlock {
            text = text == null ? "" : text;
        }
    }

    private enum BlockType {
        METADATA,
        ORIGINAL_GOAL,
        DATETIME,
        WEATHER,
        EXERCISE,
        MEAL,
        RAG,
        TODO,
        VALIDATE,
        CALCULATE
    }

    private static final class MutableBreakdown {
        private long metadata;
        private long originalGoal;
        private long datetime;
        private long weather;
        private long exercise;
        private long meal;
        private long rag;
        private long todo;
        private long validate;
        private long calculate;

        private void add(BlockType type, long chars) {
            switch (type) {
                case METADATA -> metadata += chars;
                case ORIGINAL_GOAL -> originalGoal += chars;
                case DATETIME -> datetime += chars;
                case WEATHER -> weather += chars;
                case EXERCISE -> exercise += chars;
                case MEAL -> meal += chars;
                case RAG -> rag += chars;
                case TODO -> todo += chars;
                case VALIDATE -> validate += chars;
                case CALCULATE -> calculate += chars;
            }
        }

        private Breakdown snapshot(long total) {
            Breakdown result = new Breakdown(
                    metadata,
                    originalGoal,
                    datetime,
                    weather,
                    exercise,
                    meal,
                    rag,
                    todo,
                    validate,
                    calculate,
                    total);
            if (result.explainedChars() != total) {
                throw new IllegalStateException("Synthesis context breakdown is incomplete");
            }
            return result;
        }
    }
}
