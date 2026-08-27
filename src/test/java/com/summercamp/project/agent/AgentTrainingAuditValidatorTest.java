package com.summercamp.project.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentTrainingAuditValidatorTest {
    private static final LocalDate D27 = LocalDate.parse("2026-08-27");
    private static final LocalDate D29 = LocalDate.parse("2026-08-29");
    private static final LocalDate D31 = LocalDate.parse("2026-08-31");
    private static final LocalDate D02 = LocalDate.parse("2026-09-02");

    private final AgentFinalPlanValidator validator = new AgentFinalPlanValidator();

    @Test
    void acceptsFourUniqueInRangeDatesAndDurationsWithinLimit() {
        FinalPlanValidationResult result = validate(
                trainingResult(List.of(D27, D29, D31, D02), Map.of(
                        D27, 60, D29, 45, D31, 60, D02, 50)), trainingContext());

        assertTrue(result.valid());
    }

    @Test
    void rejectsTooFewTooManyAndDuplicateTrainingDates() {
        FinalPlanValidationResult tooFew = validate(
                trainingResult(List.of(D27, D29, D31), Map.of(
                        D27, 60, D29, 60, D31, 60)), trainingContext());
        FinalPlanValidationResult tooMany = validate(
                trainingResult(List.of(D27, D29, D31, D02, LocalDate.parse("2026-08-30")),
                        Map.of(D27, 60, D29, 60, D31, 60, D02, 60,
                                LocalDate.parse("2026-08-30"), 60)), trainingContext());
        FinalPlanValidationResult duplicate = validate(
                trainingResult(List.of(D27, D27, D31, D02), Map.of(
                        D27, 60, D31, 60, D02, 60)), trainingContext());

        assertIssue(tooFew, FinalPlanValidationIssueCode.TRAINING_FREQUENCY_MISMATCH);
        assertIssue(tooMany, FinalPlanValidationIssueCode.TRAINING_FREQUENCY_MISMATCH);
        assertIssue(duplicate, FinalPlanValidationIssueCode.TRAINING_FREQUENCY_MISMATCH);
    }

    @Test
    void rejectsTrainingDateOutsidePlanAndDateAbsentFromAnswer() {
        LocalDate outside = LocalDate.parse("2026-09-03");
        FinalPlanValidationResult outsideResult = validate(
                trainingResult(List.of(D27, D29, D31, outside), Map.of(
                        D27, 60, D29, 60, D31, 60, outside, 60)), trainingContext());
        AgentSynthesisResult missingDateAnswer = trainingResult(
                List.of(D27, D29, D31, D02), Map.of(
                        D27, 60, D29, 60, D31, 60, D02, 60),
                completeAnswer().replace("8月31日（周一）：正式训练。\n", ""));
        FinalPlanValidationResult missing = validate(missingDateAnswer, trainingContext());

        assertIssue(outsideResult, FinalPlanValidationIssueCode.TRAINING_DATE_OUTSIDE_PLAN);
        assertIssue(missing, FinalPlanValidationIssueCode.TRAINING_DATE_NOT_PRESENT_IN_ANSWER);
    }

    @Test
    void rejectsExceededMissingAndMismatchedDurationCoverage() {
        FinalPlanValidationResult exceeded = validate(
                trainingResult(List.of(D27, D29, D31, D02), Map.of(
                        D27, 60, D29, 61, D31, 60, D02, 60)), trainingContext());
        FinalPlanValidationResult missing = validate(
                trainingResult(List.of(D27, D29, D31, D02), Map.of(
                        D27, 60, D29, 60, D31, 60)), trainingContext());
        LocalDate extra = LocalDate.parse("2026-08-30");
        Map<LocalDate, Integer> extraDurations = new LinkedHashMap<>(Map.of(
                D27, 60, D29, 60, D31, 60, D02, 60));
        extraDurations.put(extra, 30);
        FinalPlanValidationResult mismatch = validate(
                trainingResult(List.of(D27, D29, D31, D02), extraDurations),
                trainingContext());

        assertIssue(exceeded, FinalPlanValidationIssueCode.TRAINING_DURATION_EXCEEDED);
        assertIssue(missing, FinalPlanValidationIssueCode.TRAINING_DURATION_MISSING);
        assertIssue(mismatch, FinalPlanValidationIssueCode.TRAINING_DURATION_DATE_MISMATCH);
    }

    @Test
    void requiresAuditForExplicitTrainingAndAllowsEmptyAuditWithoutTrainingGoal() {
        FinalPlanValidationResult required = validate(
                AgentSynthesisResult.parsed(new AgentSynthesisEnvelope(
                        completeAnswer(), AgentTrainingAudit.empty())),
                trainingContext());
        FinalPlanValidationResult noTraining = validate(
                AgentSynthesisResult.answerOnly(completeAnswer()), baseContext());

        assertIssue(required, FinalPlanValidationIssueCode.TRAINING_AUDIT_MISSING);
        assertTrue(noTraining.valid());
    }

    @Test
    void missingDurationObjectFailsWhenGoalHasSessionLimit() {
        AgentTrainingAudit audit = new AgentTrainingAudit(
                true, List.of(D27, D29, D31, D02), false, Map.of());
        FinalPlanValidationResult result = validate(
                AgentSynthesisResult.parsed(new AgentSynthesisEnvelope(
                        completeAnswer(), audit)),
                trainingContext());

        assertIssue(result, FinalPlanValidationIssueCode.TRAINING_DURATION_MISSING);
    }

    private FinalPlanValidationResult validate(
            AgentSynthesisResult result,
            String context
    ) {
        AgentPlan plan = new AgentPlan("未来7天健康规划", List.of());
        return validator.validate(
                plan.goal(), plan, new AgentState(plan), context, result);
    }

    private AgentSynthesisResult trainingResult(
            List<LocalDate> dates,
            Map<LocalDate, Integer> durations
    ) {
        return trainingResult(dates, durations, completeAnswer());
    }

    private AgentSynthesisResult trainingResult(
            List<LocalDate> dates,
            Map<LocalDate, Integer> durations,
            String answer
    ) {
        return AgentSynthesisResult.parsed(new AgentSynthesisEnvelope(
                answer, AgentTrainingAudit.complete(dates, durations)));
    }

    private void assertIssue(
            FinalPlanValidationResult result,
            FinalPlanValidationIssueCode issue
    ) {
        assertFalse(result.valid());
        assertTrue(result.issues().contains(issue), () -> "issues=" + result.issues());
    }

    private String trainingContext() {
        return baseContext()
                + "TRAINING_FREQUENCY_PER_WEEK=4\n"
                + "TRAINING_SESSION_TOTAL_MINUTES=60\n";
    }

    private String baseContext() {
        return """
                PLAN_START_DATE=2026-08-27
                PLAN_END_DATE=2026-09-02
                """;
    }

    private String completeAnswer() {
        return """
                8月27日（周四）：正式训练。
                8月28日（周五）：恢复。
                8月29日（周六）：正式训练。
                8月30日（周日）：恢复。
                8月31日（周一）：正式训练。
                9月1日（周二）：恢复。
                9月2日（周三）：正式训练。
                9月3日：仅用于越界测试。
                """;
    }
}
