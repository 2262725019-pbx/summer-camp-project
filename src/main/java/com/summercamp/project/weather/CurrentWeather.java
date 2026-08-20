package com.summercamp.project.weather;

public record CurrentWeather(
        String weather,
        String temperature,
        String humidity,
        String windDirection,
        String windPower) {
}
