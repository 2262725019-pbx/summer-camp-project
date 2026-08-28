package com.summercamp.project.conversation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** High-confidence, allowlisted fact extraction. This class never calls an LLM. */
public final class SessionFactExtractor {

    static final int MAX_FACT_VALUE_CHARS = 200;

    private static final String FIELD_LABELS =
            "演示地点|答辩重点|演示顺序|后端语言|运动目标|运动偏好|每周训练|每次训练|每日餐数";
    private static final Pattern EXPLICIT_FIELD = Pattern.compile(
            "(?m)(?:^|[\\r\\n;；])\\s*(" + FIELD_LABELS
                    + ")\\s*[:：]\\s*([^\\r\\n;；]+)");
    private static final Pattern LOCATION_UPDATE = Pattern.compile(
            "(?:\\A|[\\r\\n。！？；;])\\s*(?:这次)?(?:演示)?地点\\s*改成\\s*"
                    + "([^，,。.!！?？;；\\r\\n]+)");
    private static final Pattern FOCUS_UPDATE = Pattern.compile(
            "(?:\\A|[\\r\\n。！？；;])\\s*答辩重点\\s*改成\\s*"
                    + "([^，,。.!！?？;；\\r\\n]+)");
    private static final Pattern EXPLICIT_REMOVAL = Pattern.compile(
            "(?:\\A|[\\r\\n。！？；;])\\s*(?:忘掉我的|清除)\\s*(" + FIELD_LABELS + ")"
                    + "(?=$|[，,。.!！?？;；\\r\\n])");
    private static final Pattern SECRET_LABEL = Pattern.compile(
            "(?i)(?:api[-_ ]?key|password|authorization|secret|access[-_ ]?token|token|密码|密钥)");
    private static final Pattern SECRET_PREFIX = Pattern.compile(
            "(?i)(?:^|\\s)(?:sk|ak|key)-[a-z0-9_-]{4,}");
    private static final Pattern TRAILING_PUNCTUATION = Pattern.compile("[。.!！?？]+$");

    public Extraction extract(String text) {
        if (text == null || text.isBlank()) {
            return Extraction.empty();
        }
        List<PositionedMutation> found = new ArrayList<>();
        collectFields(text, found);
        collectUpdate(text, LOCATION_UPDATE, SessionFactKey.LOCATION, found);
        collectUpdate(text, FOCUS_UPDATE, SessionFactKey.DEMO_FOCUS, found);
        collectRemovals(text, found);
        found.sort(Comparator.comparingInt(PositionedMutation::position));
        return new Extraction(found.stream().map(PositionedMutation::mutation).toList());
    }

    private void collectFields(String text, List<PositionedMutation> found) {
        Matcher matcher = EXPLICIT_FIELD.matcher(text);
        while (matcher.find()) {
            SessionFactKey key = keyForLabel(matcher.group(1));
            normalizedValue(key, matcher.group(2)).ifPresent(value -> found.add(
                    new PositionedMutation(
                            matcher.start(1),
                            Mutation.upsert(key, value, SessionFactSourceType.EXPLICIT_FIELD))));
        }
    }

    private void collectUpdate(
            String text,
            Pattern pattern,
            SessionFactKey key,
            List<PositionedMutation> found) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            normalizedValue(key, matcher.group(1)).ifPresent(value -> found.add(
                    new PositionedMutation(
                            matcher.start(1),
                            Mutation.upsert(key, value, SessionFactSourceType.EXPLICIT_UPDATE))));
        }
    }

    private void collectRemovals(String text, List<PositionedMutation> found) {
        Matcher matcher = EXPLICIT_REMOVAL.matcher(text);
        while (matcher.find()) {
            found.add(new PositionedMutation(
                    matcher.start(1), Mutation.remove(keyForLabel(matcher.group(1)))));
        }
    }

    private java.util.Optional<String> normalizedValue(SessionFactKey key, String rawValue) {
        String value = TRAILING_PUNCTUATION.matcher(rawValue.strip()).replaceFirst("").strip();
        if (value.isBlank() || containsSecret(value)) {
            return java.util.Optional.empty();
        }
        if (value.length() > MAX_FACT_VALUE_CHARS) {
            value = value.substring(0, MAX_FACT_VALUE_CHARS).strip();
        }
        return switch (key) {
            case TRAINING_FREQUENCY_PER_WEEK -> normalizedNumber(value, "次", 1, 21);
            case TRAINING_DURATION_MINUTES -> normalizedNumber(value, "分钟", 1, 600);
            case DAILY_MEAL_COUNT -> normalizedNumber(value, "餐", 1, 12);
            default -> java.util.Optional.of(value);
        };
    }

    private java.util.Optional<String> normalizedNumber(
            String value, String unit, int minimum, int maximum) {
        Matcher matcher = Pattern.compile("^(\\d{1,3})\\s*(?:" + unit + ")?$").matcher(value);
        if (!matcher.matches()) {
            return java.util.Optional.empty();
        }
        int parsed = Integer.parseInt(matcher.group(1));
        return parsed >= minimum && parsed <= maximum
                ? java.util.Optional.of(Integer.toString(parsed))
                : java.util.Optional.empty();
    }

    private boolean containsSecret(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return SECRET_LABEL.matcher(normalized).find() || SECRET_PREFIX.matcher(normalized).find();
    }

    private SessionFactKey keyForLabel(String label) {
        return switch (label) {
            case "演示地点" -> SessionFactKey.LOCATION;
            case "答辩重点" -> SessionFactKey.DEMO_FOCUS;
            case "演示顺序" -> SessionFactKey.DEMO_ORDER;
            case "后端语言" -> SessionFactKey.PREFERRED_BACKEND_LANGUAGE;
            case "运动目标" -> SessionFactKey.EXERCISE_GOAL;
            case "运动偏好" -> SessionFactKey.EXERCISE_PREFERENCE;
            case "每周训练" -> SessionFactKey.TRAINING_FREQUENCY_PER_WEEK;
            case "每次训练" -> SessionFactKey.TRAINING_DURATION_MINUTES;
            case "每日餐数" -> SessionFactKey.DAILY_MEAL_COUNT;
            default -> throw new IllegalArgumentException("Unsupported fact label");
        };
    }

    public record Extraction(List<Mutation> mutations) {

        public Extraction {
            mutations = List.copyOf(mutations);
        }

        static Extraction empty() {
            return new Extraction(List.of());
        }

        public int extractedCount() {
            return (int) mutations.stream().filter(Mutation::upsert).count();
        }
    }

    public record Mutation(
            SessionFactKey key,
            String value,
            SessionFactSourceType sourceType,
            boolean upsert) {

        static Mutation upsert(
                SessionFactKey key, String value, SessionFactSourceType sourceType) {
            return new Mutation(key, value, sourceType, true);
        }

        static Mutation remove(SessionFactKey key) {
            return new Mutation(key, "", null, false);
        }
    }

    private record PositionedMutation(int position, Mutation mutation) {
    }
}
