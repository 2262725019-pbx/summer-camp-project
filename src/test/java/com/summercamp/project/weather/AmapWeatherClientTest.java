package com.summercamp.project.weather;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summercamp.project.config.WeatherProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class AmapWeatherClientTest {

    private static final String DISTRICT = """
            {"status":"1","districts":[{"name":"袁州区","adcode":"360902"}]}
            """;
    private static final String CURRENT = """
            {"status":"1","lives":[{
              "city":"袁州区","weather":"晴","temperature":"31",
              "humidity":"55","winddirection":"东南","windpower":"3",
              "reporttime":"2026-08-18 11:00:00"
            }]}
            """;
    private static final String FORECAST = """
            {"status":"1","forecasts":[{
              "city":"袁州区","reporttime":"2026-08-18 11:00:00","casts":[
                {"date":"2026-08-18","week":"2","dayweather":"晴","nightweather":"多云","daytemp":"34","nighttemp":"25","daywind":"南","nightwind":"南","daypower":"3","nightpower":"3"},
                {"date":"2026-08-19","week":"3","dayweather":"雷阵雨","nightweather":"多云","daytemp":"32","nighttemp":"24","daywind":"东","nightwind":"东","daypower":"3","nightpower":"3"},
                {"date":"2026-08-20","week":"4","dayweather":"多云","nightweather":"晴","daytemp":"33","nighttemp":"25","daywind":"北","nightwind":"北","daypower":"2","nightpower":"2"}
              ]
            }]}
            """;

    @Test
    void queriesCurrentWeatherWithResolvedAdcode() {
        StubClient client = client(path -> path.contains("/district") ? DISTRICT : CURRENT);

        WeatherReport report = client.query("江西省宜春市袁州区", WeatherPeriod.CURRENT);

        assertEquals("袁州区", report.location());
        assertEquals("31", report.current().temperature());
        assertTrue(client.lastPath.contains("city=360902"));
        assertTrue(client.lastPath.contains("extensions=base"));
        assertTrue(report.formatChinese().contains("数据发布时间：2026-08-18 11:00:00"));
    }

    @Test
    void selectsTomorrowAndThreeDayForecasts() {
        StubClient tomorrowClient = client(path -> path.contains("/district") ? DISTRICT : FORECAST);
        StubClient threeDayClient = client(path -> path.contains("/district") ? DISTRICT : FORECAST);

        WeatherReport tomorrow = tomorrowClient.query("宜春市袁州区", WeatherPeriod.TOMORROW);
        WeatherReport threeDays = threeDayClient.query("宜春市袁州区", WeatherPeriod.THREE_DAYS);

        assertEquals(1, tomorrow.forecasts().size());
        assertEquals("2026-08-19", tomorrow.forecasts().getFirst().date());
        assertEquals(3, threeDays.forecasts().size());
        assertTrue(threeDayClient.lastPath.contains("extensions=all"));
    }

    @Test
    void rejectsAmbiguousDistrictAndAmapErrorResponse() {
        StubClient ambiguous = client(path -> """
                {"status":"1","districts":[
                  {"name":"鼓楼区","adcode":"320106"},
                  {"name":"鼓楼区","adcode":"350102"}
                ]}
                """);
        StubClient failed = client(path -> """
                {"status":"0","info":"INVALID_USER_KEY","districts":[]}
                """);

        assertThrows(WeatherLocationAmbiguousException.class,
                () -> ambiguous.query("鼓楼区", WeatherPeriod.CURRENT));
        WeatherException exception = assertThrows(WeatherException.class,
                () -> failed.query("袁州区", WeatherPeriod.CURRENT));
        assertTrue(exception.getMessage().contains("INVALID_USER_KEY"));
    }

    private StubClient client(Function<String, String> responseProvider) {
        WeatherProperties properties = new WeatherProperties(
                "https://restapi.amap.com", "test-web-service-key", Duration.ofSeconds(10));
        return new StubClient(properties, new ObjectMapper(), responseProvider);
    }

    private static final class StubClient extends AmapWeatherClient {
        private final ObjectMapper mapper;
        private final Function<String, String> responseProvider;
        private String lastPath = "";

        private StubClient(
                WeatherProperties properties,
                ObjectMapper mapper,
                Function<String, String> responseProvider) {
            super(properties, mapper, HttpClient.newHttpClient());
            this.mapper = mapper;
            this.responseProvider = responseProvider;
        }

        @Override
        JsonNode getJson(String pathAndQuery) {
            lastPath = pathAndQuery;
            try {
                return mapper.readTree(responseProvider.apply(pathAndQuery));
            } catch (JsonProcessingException exception) {
                throw new AssertionError(exception);
            }
        }
    }
}
