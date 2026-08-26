package com.summercamp.project.agent;

import com.summercamp.project.skill.TrustedWeatherObservation;
import com.summercamp.project.weather.WeatherPeriod;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Resolves one unambiguous, completed weather observation preceding the consumer in this run. */
final class TrustedWeatherObservationResolver {
    private static final String WEATHER_TOOL = "get_weather";

    Decision resolve(
            AgentStep consumerStep,
            AgentExecutionContext context
    ) {
        if (consumerStep.action() != AgentAction.RUN_EXERCISE_SKILL) {
            return Decision.rejected(Reason.NO_COMPLETED_WEATHER);
        }

        List<AgentStep> weatherSteps = context.plan().steps().stream()
                .filter(step -> step.action() == AgentAction.GET_WEATHER)
                .toList();
        if (weatherSteps.isEmpty()) {
            return Decision.rejected(Reason.NO_COMPLETED_WEATHER);
        }

        int consumerIndex = context.plan().steps().indexOf(consumerStep);
        if (consumerIndex < 0) {
            return Decision.rejected(Reason.WEATHER_NOT_PREDECESSOR);
        }
        List<AgentStep> priorWeatherSteps = weatherSteps.stream()
                .filter(step -> context.plan().steps().indexOf(step) < consumerIndex)
                .toList();
        if (priorWeatherSteps.isEmpty()) {
            return weatherSteps.stream().anyMatch(step -> context.state().isStepCompleted(step.id()))
                    ? Decision.rejected(Reason.WEATHER_NOT_PREDECESSOR)
                    : Decision.rejected(reasonWithoutCompletedWeather(weatherSteps, context.state()));
        }

        List<AgentStep> completedPriorWeatherSteps = priorWeatherSteps.stream()
                .filter(step -> context.state().isStepCompleted(step.id()))
                .toList();
        if (completedPriorWeatherSteps.isEmpty()) {
            return Decision.rejected(reasonWithoutCompletedWeather(
                    priorWeatherSteps, context.state()));
        }
        if (completedPriorWeatherSteps.size() > 1) {
            return Decision.rejected(Reason.AMBIGUOUS_WEATHER_OBSERVATIONS);
        }

        return trustedObservation(completedPriorWeatherSteps.getFirst(), context.state());
    }

    private Reason reasonWithoutCompletedWeather(
            List<AgentStep> weatherSteps,
            AgentStateView state
    ) {
        boolean failedObservation = weatherSteps.stream()
                .map(step -> state.findObservation(step.id()))
                .flatMap(Optional::stream)
                .anyMatch(observation -> !observation.success());
        return failedObservation
                ? Reason.WEATHER_OBSERVATION_FAILED
                : Reason.NO_COMPLETED_WEATHER;
    }

    private Decision trustedObservation(
            AgentStep weatherStep,
            AgentStateView state
    ) {
        if (state.statusOf(weatherStep.id()) != AgentStepStatus.COMPLETED) {
            return Decision.rejected(Reason.NO_COMPLETED_WEATHER);
        }
        Optional<AgentObservation> observation = state.findObservation(weatherStep.id());
        if (observation.isEmpty() || !observation.orElseThrow().success()) {
            return Decision.rejected(Reason.WEATHER_OBSERVATION_FAILED);
        }

        Map<String, String> data = observation.orElseThrow().structuredData();
        String location = data.get("location");
        String period = data.get("period");
        if (!WEATHER_TOOL.equals(data.get("tool"))) {
            return Decision.rejected(Reason.WEATHER_SOURCE_MISMATCH);
        }
        String plannedLocation = weatherStep.inputs().get("location");
        if (!sameLocation(location, plannedLocation)
                || !singleLine(location)
                || !singleLine(plannedLocation)) {
            return Decision.rejected(Reason.WEATHER_LOCATION_MISMATCH);
        }
        if (!samePeriod(period, weatherStep.inputs().get("period"))) {
            return Decision.rejected(Reason.WEATHER_PERIOD_MISMATCH);
        }
        String modelContent = data.get("modelContent");
        if (modelContent == null
                || modelContent.isBlank()
                || modelContent.strip().endsWith("…")) {
            return Decision.rejected(Reason.WEATHER_CONTENT_INCOMPLETE);
        }
        Optional<TrustedWeatherObservation> trusted =
                TrustedWeatherObservation.create(location, period, modelContent);
        return trusted
                .map(Decision::eligible)
                .orElseGet(() -> Decision.rejected(Reason.WEATHER_CONTEXT_TOO_LARGE));
    }

    private boolean sameLocation(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return canonicalCity(left).equalsIgnoreCase(canonicalCity(right));
    }

    private String canonicalCity(String value) {
        String normalized = value.strip();
        return normalized.length() > 1 && normalized.endsWith("市")
                ? normalized.substring(0, normalized.length() - 1)
                : normalized;
    }

    private boolean singleLine(String value) {
        return value != null && !value.contains("\n") && !value.contains("\r");
    }

    private boolean samePeriod(String left, String right) {
        try {
            WeatherPeriod leftPeriod = WeatherPeriod.valueOf(
                    left == null ? "" : left.strip().toUpperCase(Locale.ROOT));
            WeatherPeriod rightPeriod = WeatherPeriod.valueOf(
                    right == null ? "" : right.strip().toUpperCase(Locale.ROOT));
            return leftPeriod == rightPeriod;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    record Decision(Optional<TrustedWeatherObservation> observation, Reason reason) {
        Decision {
            observation = observation == null ? Optional.empty() : observation;
            if (reason == null) {
                throw new IllegalArgumentException("reason must not be null");
            }
            if ((reason == Reason.ELIGIBLE) != observation.isPresent()) {
                throw new IllegalArgumentException("eligible reason and observation must agree");
            }
        }

        static Decision eligible(TrustedWeatherObservation observation) {
            return new Decision(Optional.of(observation), Reason.ELIGIBLE);
        }

        static Decision rejected(Reason reason) {
            return new Decision(Optional.empty(), reason);
        }

        boolean eligible() {
            return reason == Reason.ELIGIBLE;
        }
    }

    enum Reason {
        NO_COMPLETED_WEATHER,
        WEATHER_NOT_PREDECESSOR,
        WEATHER_NOT_DEPENDENCY_ANCESTOR,
        WEATHER_OBSERVATION_FAILED,
        WEATHER_SOURCE_MISMATCH,
        WEATHER_LOCATION_MISMATCH,
        WEATHER_PERIOD_MISMATCH,
        WEATHER_CONTENT_INCOMPLETE,
        WEATHER_CONTEXT_TOO_LARGE,
        AMBIGUOUS_WEATHER_OBSERVATIONS,
        ELIGIBLE
    }
}
