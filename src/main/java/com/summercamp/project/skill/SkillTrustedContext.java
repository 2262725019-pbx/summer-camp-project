package com.summercamp.project.skill;

import java.util.Optional;

/** Typed application-only grounding. User text is never parsed into this context. */
public record SkillTrustedContext(Optional<TrustedWeatherObservation> weatherObservation) {
    private static final SkillTrustedContext EMPTY = new SkillTrustedContext(Optional.empty());

    public SkillTrustedContext {
        weatherObservation = weatherObservation == null ? Optional.empty() : weatherObservation;
    }

    public static SkillTrustedContext empty() {
        return EMPTY;
    }

    public static SkillTrustedContext withWeather(TrustedWeatherObservation observation) {
        return new SkillTrustedContext(Optional.of(observation));
    }
}
