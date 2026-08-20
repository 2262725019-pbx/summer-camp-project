package com.summercamp.project.weather;

public class WeatherLocationAmbiguousException extends WeatherException {

    public WeatherLocationAmbiguousException(String location) {
        super("找到多个名为“" + location + "”的地区");
    }
}
