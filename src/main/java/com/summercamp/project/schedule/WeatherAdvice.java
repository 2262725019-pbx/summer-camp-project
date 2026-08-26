package com.summercamp.project.schedule;

import com.summercamp.project.weather.WeatherReport;

/**
 * 天气播报附带的运动建议（本地规则，不依赖大模型，保证定时推送稳定快速）。
 */
public final class WeatherAdvice {

    private WeatherAdvice() {
    }

    public static String adviceFor(WeatherReport report) {
        String weather = "";
        int temperature = Integer.MIN_VALUE;
        if (report.current() != null) {
            weather = report.current().weather();
            temperature = parseInt(report.current().temperature());
        } else if (!report.forecasts().isEmpty()) {
            com.summercamp.project.weather.ForecastDay day = report.forecasts().getFirst();
            weather = day.dayWeather() + day.nightWeather();
            temperature = parseInt(day.dayTemperature());
        }
        if (weather.contains("雨") || weather.contains("雪")) {
            return "今天有雨雪，户外运动建议改为室内替代（跳绳、健身操、力量训练等）。";
        }
        if (temperature >= 33) {
            return "今天气温较高，外出运动请避开正午时段，注意补水防暑。";
        }
        if (temperature != Integer.MIN_VALUE && temperature <= 10) {
            return "今天气温较低，运动前请充分热身保暖，注意防寒。";
        }
        return "今天天气适合运动，记得运动前热身、结束后拉伸放松。";
    }

    private static int parseInt(String value) {
        if (value == null || value.isBlank()) {
            return Integer.MIN_VALUE;
        }
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException exception) {
            return Integer.MIN_VALUE;
        }
    }
}
