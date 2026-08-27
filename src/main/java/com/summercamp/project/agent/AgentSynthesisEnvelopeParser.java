package com.summercamp.project.agent;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict parser for the minimal Agent synthesis JSON contract. */
public final class AgentSynthesisEnvelopeParser {
    private static final Set<String> ROOT_FIELDS = Set.of("answer", "audit");
    private static final Set<String> AUDIT_FIELDS = Set.of(
            "trainingDates", "sessionDurationMinutesByDate");

    private final ObjectMapper objectMapper;

    public AgentSynthesisEnvelopeParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    public AgentSynthesisResult parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return AgentSynthesisResult.invalid(AgentSynthesisParseError.MALFORMED_JSON);
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(raw);
        } catch (JsonProcessingException exception) {
            return AgentSynthesisResult.invalid(AgentSynthesisParseError.MALFORMED_JSON);
        }
        if (root == null || !root.isObject() || hasUnexpectedFields(root, ROOT_FIELDS)) {
            return AgentSynthesisResult.invalid(AgentSynthesisParseError.INVALID_SCHEMA);
        }
        JsonNode answer = root.get("answer");
        if (answer == null || !answer.isTextual() || answer.asText().isBlank()) {
            return AgentSynthesisResult.invalid(AgentSynthesisParseError.INVALID_SCHEMA);
        }
        JsonNode audit = root.get("audit");
        if (audit == null || audit.isNull()) {
            return AgentSynthesisResult.invalid(AgentSynthesisParseError.AUDIT_MISSING);
        }
        if (!audit.isObject() || hasUnexpectedFields(audit, AUDIT_FIELDS)) {
            return AgentSynthesisResult.invalid(AgentSynthesisParseError.INVALID_SCHEMA);
        }

        ParsedDates dates = parseTrainingDates(audit.get("trainingDates"));
        if (dates.error() != null) {
            return AgentSynthesisResult.invalid(dates.error());
        }
        ParsedDurations durations = parseDurations(
                audit.get("sessionDurationMinutesByDate"));
        if (durations.error() != null) {
            return AgentSynthesisResult.invalid(durations.error());
        }
        AgentTrainingAudit trainingAudit = new AgentTrainingAudit(
                dates.present(), dates.values(), durations.present(), durations.values());
        return AgentSynthesisResult.parsed(new AgentSynthesisEnvelope(
                answer.asText(), trainingAudit));
    }

    private ParsedDates parseTrainingDates(JsonNode node) {
        if (node == null) {
            return new ParsedDates(false, List.of(), null);
        }
        if (!node.isArray()) {
            return new ParsedDates(true, List.of(), AgentSynthesisParseError.INVALID_SCHEMA);
        }
        List<LocalDate> dates = new ArrayList<>();
        Set<LocalDate> unique = new HashSet<>();
        for (JsonNode item : node) {
            LocalDate date = parseIsoDate(item);
            if (date == null) {
                return new ParsedDates(true, List.of(), AgentSynthesisParseError.INVALID_SCHEMA);
            }
            if (!unique.add(date)) {
                return new ParsedDates(
                        true, List.of(), AgentSynthesisParseError.DUPLICATE_TRAINING_DATE);
            }
            dates.add(date);
        }
        return new ParsedDates(true, List.copyOf(dates), null);
    }

    private ParsedDurations parseDurations(JsonNode node) {
        if (node == null) {
            return new ParsedDurations(false, Map.of(), null);
        }
        if (!node.isObject()) {
            return new ParsedDurations(true, Map.of(), AgentSynthesisParseError.INVALID_SCHEMA);
        }
        Map<LocalDate, Integer> durations = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            LocalDate date = parseIsoDate(field.getKey());
            JsonNode value = field.getValue();
            if (date == null || !value.isIntegralNumber() || !value.canConvertToInt()
                    || value.intValue() <= 0) {
                return new ParsedDurations(
                        true, Map.of(), AgentSynthesisParseError.INVALID_SCHEMA);
            }
            durations.put(date, value.intValue());
        }
        return new ParsedDurations(true, Map.copyOf(durations), null);
    }

    private LocalDate parseIsoDate(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        return parseIsoDate(node.asText());
    }

    private LocalDate parseIsoDate(String raw) {
        try {
            LocalDate date = LocalDate.parse(raw);
            return date.toString().equals(raw) ? date : null;
        } catch (DateTimeException exception) {
            return null;
        }
    }

    private boolean hasUnexpectedFields(JsonNode node, Set<String> allowed) {
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            if (!allowed.contains(names.next())) {
                return true;
            }
        }
        return false;
    }

    private record ParsedDates(
            boolean present,
            List<LocalDate> values,
            AgentSynthesisParseError error
    ) {
    }

    private record ParsedDurations(
            boolean present,
            Map<LocalDate, Integer> values,
            AgentSynthesisParseError error
    ) {
    }
}
