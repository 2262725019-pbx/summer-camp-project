package com.summercamp.project.weather;

public class WeatherLocationNotFoundException extends WeatherException {

    public WeatherLocationNotFoundException(String location) {
        super("没有找到地区“" + location + "”");
    }
}
