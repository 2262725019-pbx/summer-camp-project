package com.summercamp.project.weather;

import java.util.List;

public record WeatherReport(
        String location,
        String reportTime,
        WeatherPeriod period,
        CurrentWeather current,
        List<ForecastDay> forecasts) {

    public WeatherReport {
        forecasts = forecasts == null ? List.of() : List.copyOf(forecasts);
    }

    public String formatChinese() {
        if (current != null) {
            return "%s实时天气：%s，%s℃，湿度%s%%，%s%s级。数据发布时间：%s。"
                    .formatted(
                            location,
                            current.weather(),
                            current.temperature(),
                            current.humidity(),
                            current.windDirection(),
                            current.windPower(),
                            reportTime);
        }
        if (forecasts.isEmpty()) {
            throw new WeatherException("高德天气响应中没有可用的预报数据");
        }
        StringBuilder answer = new StringBuilder(location).append("天气预报：\n");
        for (ForecastDay day : forecasts) {
            answer.append(day.date())
                    .append("（周").append(day.week()).append("）：白天")
                    .append(day.dayWeather()).append('，').append(day.dayTemperature()).append("℃，")
                    .append(day.dayWind()).append(day.dayPower()).append("级；夜间")
                    .append(day.nightWeather()).append('，').append(day.nightTemperature()).append("℃，")
                    .append(day.nightWind()).append(day.nightPower()).append("级。\n");
        }
        return answer.append("数据发布时间：").append(reportTime).append('。').toString();
    }
}
