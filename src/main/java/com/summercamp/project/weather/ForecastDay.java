package com.summercamp.project.weather;

public record ForecastDay(
        String date,
        String week,
        String dayWeather,
        String nightWeather,
        String dayTemperature,
        String nightTemperature,
        String dayWind,
        String nightWind,
        String dayPower,
        String nightPower) {
}
