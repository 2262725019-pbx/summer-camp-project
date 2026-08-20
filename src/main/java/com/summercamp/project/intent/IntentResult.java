package com.summercamp.project.intent;

import com.summercamp.project.weather.WeatherPeriod;

public record IntentResult(
        IntentType type,
        String location,
        WeatherPeriod weatherPeriod,
        String prompt) {

    public IntentResult {
        type = type == null ? IntentType.CHAT : type;
        location = location == null ? "" : location.strip();
        weatherPeriod = weatherPeriod == null ? WeatherPeriod.CURRENT : weatherPeriod;
        prompt = prompt == null ? "" : prompt.strip();
    }

    public static IntentResult chat() {
        return new IntentResult(IntentType.CHAT, "", WeatherPeriod.CURRENT, "");
    }

    public static IntentResult simple(IntentType type) {
        return new IntentResult(type, "", WeatherPeriod.CURRENT, "");
    }

    public static IntentResult weather(String location, WeatherPeriod period) {
        return new IntentResult(IntentType.WEATHER, location, period, "");
    }

    public static IntentResult imageGeneration(String prompt) {
        return new IntentResult(
                IntentType.IMAGE_GENERATION,
                "",
                WeatherPeriod.CURRENT,
                prompt);
    }
}
