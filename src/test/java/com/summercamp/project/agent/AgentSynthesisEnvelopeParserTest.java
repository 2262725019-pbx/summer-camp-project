package com.summercamp.project.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentSynthesisEnvelopeParserTest {
    private final AgentSynthesisEnvelopeParser parser = new AgentSynthesisEnvelopeParser(
            new ObjectMapper());

    @Test
    void parsesMinimalTypedTrainingEnvelope() {
        AgentSynthesisResult result = parser.parse("""
                {
                  "answer":"完整计划",
                  "audit":{
                    "trainingDates":["2026-08-27","2026-08-29"],
                    "sessionDurationMinutesByDate":{
                      "2026-08-27":60,
                      "2026-08-29":45
                    }
                  }
                }
                """);

        assertTrue(result.parsed());
        assertEquals("完整计划", result.answer());
        assertEquals(
                List.of(LocalDate.parse("2026-08-27"), LocalDate.parse("2026-08-29")),
                result.audit().trainingDates());
        assertEquals(60, result.audit().sessionDurationMinutesByDate()
                .get(LocalDate.parse("2026-08-27")));
    }

    @Test
    void rejectsMalformedJsonAndUnexpectedFields() {
        AgentSynthesisResult malformed = parser.parse("{not-json");
        AgentSynthesisResult extra = parser.parse("""
                {"answer":"计划","audit":{},"weather":{}}
                """);

        assertEquals(AgentSynthesisParseError.MALFORMED_JSON,
                malformed.parseError().orElseThrow());
        assertEquals(AgentSynthesisParseError.INVALID_SCHEMA,
                extra.parseError().orElseThrow());
    }

    @Test
    void rejectsMissingAuditDuplicateDatesAndInvalidDurations() {
        AgentSynthesisResult missing = parser.parse("{\"answer\":\"计划\"}");
        AgentSynthesisResult duplicate = parser.parse("""
                {"answer":"计划","audit":{"trainingDates":["2026-08-27","2026-08-27"]}}
                """);
        AgentSynthesisResult nonInteger = parser.parse("""
                {"answer":"计划","audit":{"sessionDurationMinutesByDate":{"2026-08-27":60.5}}}
                """);
        AgentSynthesisResult zero = parser.parse("""
                {"answer":"计划","audit":{"sessionDurationMinutesByDate":{"2026-08-27":0}}}
                """);

        assertFalse(missing.parsed());
        assertEquals(AgentSynthesisParseError.AUDIT_MISSING,
                missing.parseError().orElseThrow());
        assertEquals(AgentSynthesisParseError.DUPLICATE_TRAINING_DATE,
                duplicate.parseError().orElseThrow());
        assertEquals(AgentSynthesisParseError.INVALID_SCHEMA,
                nonInteger.parseError().orElseThrow());
        assertEquals(AgentSynthesisParseError.INVALID_SCHEMA,
                zero.parseError().orElseThrow());
    }
}
