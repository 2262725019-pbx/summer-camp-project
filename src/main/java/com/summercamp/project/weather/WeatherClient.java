package com.summercamp.project.weather;

public interface WeatherClient {

    WeatherReport query(String location, WeatherPeriod period);
}
