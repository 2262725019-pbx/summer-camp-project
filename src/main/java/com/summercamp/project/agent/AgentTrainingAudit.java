package com.summercamp.project.agent;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal structured facts declared by final synthesis solely for consistency validation. */
public record AgentTrainingAudit(
        boolean trainingDatesPresent,
        List<LocalDate> trainingDates,
        boolean sessionDurationsPresent,
        Map<LocalDate, Integer> sessionDurationMinutesByDate
) {
    public AgentTrainingAudit {
        trainingDates = trainingDates == null ? List.of() : List.copyOf(trainingDates);
        sessionDurationMinutesByDate = sessionDurationMinutesByDate == null
                ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(sessionDurationMinutesByDate));
    }

    public static AgentTrainingAudit empty() {
        return new AgentTrainingAudit(false, List.of(), false, Map.of());
    }

    public static AgentTrainingAudit complete(
            List<LocalDate> trainingDates,
            Map<LocalDate, Integer> sessionDurationMinutesByDate
    ) {
        return new AgentTrainingAudit(
                true, trainingDates, true, sessionDurationMinutesByDate);
    }
}
