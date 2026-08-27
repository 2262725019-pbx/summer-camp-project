package com.summercamp.project.weather;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summercamp.project.config.WeatherProperties;
import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class AmapWeatherClient implements WeatherClient {

    private static final Pattern MOST_SPECIFIC_DISTRICT = Pattern.compile(
            "([^省市区县]{1,16}(?:自治州|地区|盟|市|区|县))$");

    private final WeatherProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public AmapWeatherClient(
            WeatherProperties properties,
            ObjectMapper objectMapper,
            HttpClient httpClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @Override
    public WeatherReport query(String location, WeatherPeriod period) {
        properties.validate();
        ResolvedLocation resolved = resolveLocation(location);
        boolean current = period == WeatherPeriod.CURRENT;
        JsonNode response = getJson("/v3/weather/weatherInfo?city="
                + encode(resolved.adcode())
                + "&extensions=" + (current ? "base" : "all")
                + "&output=JSON&key=" + encode(properties.apiKey()));
        ensureSuccess(response, "高德天气查询失败");
        return current
                ? parseCurrent(response, resolved, period)
                : parseForecast(response, resolved, period);
    }

    ResolvedLocation resolveLocation(String location) {
        String normalized = location == null ? "" : location.strip();
        if (normalized.isBlank()) {
            throw new WeatherLocationNotFoundException("");
        }
        List<ResolvedLocation> matches = queryDistricts(normalized);
        if (matches.isEmpty()) {
            String mostSpecific = mostSpecificPart(normalized);
            if (!mostSpecific.equals(normalized)) {
                matches = queryDistricts(mostSpecific);
            }
        }
        if (matches.isEmpty()) {
            throw new WeatherLocationNotFoundException(normalized);
        }

        // 优先精确匹配（名称完全相同或输入以名称结尾）
        List<ResolvedLocation> exact = matches.stream()
            .filter(match -> normalized.endsWith(match.name()) || normalized.equals(match.name()))
            .toList();
        if (exact.size() == 1) {
            return exact.getFirst();
        }

        // 如果精确匹配为空，但存在多个结果，尝试选择城市级别（adcode 以 00 结尾）
        if (exact.isEmpty() && matches.size() > 1) {
            List<ResolvedLocation> cityLevel = matches.stream()
                .filter(m -> m.adcode().endsWith("00"))
                .toList();
            if (cityLevel.size() == 1) {
                return cityLevel.getFirst();
            }
            if (!cityLevel.isEmpty()) {
                // 如果仍有多个城市级，选择名称最短的（通常最通用）
                return cityLevel.stream()
                    .min(Comparator.comparingInt(m -> m.name().length()))
                    .orElseThrow();
            }
        }

        // 如果只有一个匹配，直接返回
        if (matches.size() == 1) {
            return matches.getFirst();
        }

        // 其他情况仍报歧义
        throw new WeatherLocationAmbiguousException(normalized);
    }

    private List<ResolvedLocation> queryDistricts(String keyword) {
        JsonNode response = getJson("/v3/config/district?keywords=" + encode(keyword)
                + "&subdistrict=0&extensions=base&output=JSON&key=" + encode(properties.apiKey()));
        ensureSuccess(response, "高德地区查询失败");
        List<ResolvedLocation> locations = new ArrayList<>();
        for (JsonNode district : response.path("districts")) {
            String name = district.path("name").asText().strip();
            String adcode = district.path("adcode").asText().strip();
            if (!name.isBlank() && !adcode.isBlank()) {
                locations.add(new ResolvedLocation(name, adcode));
            }
        }
        return locations;
    }

    private WeatherReport parseCurrent(
            JsonNode response,
            ResolvedLocation location,
            WeatherPeriod period) {
        JsonNode live = response.path("lives").path(0);
        if (live.isMissingNode()) {
            throw new WeatherException("高德天气响应中没有实时天气数据");
        }
        String displayName = textOrDefault(live, "city", location.name());
        CurrentWeather current = new CurrentWeather(
                requiredText(live, "weather"),
                requiredText(live, "temperature"),
                requiredText(live, "humidity"),
                requiredText(live, "winddirection"),
                requiredText(live, "windpower"));
        return new WeatherReport(
                displayName,
                requiredText(live, "reporttime"),
                period,
                current,
                List.of());
    }

    private WeatherReport parseForecast(
            JsonNode response,
            ResolvedLocation location,
            WeatherPeriod period) {
        JsonNode forecast = response.path("forecasts").path(0);
        if (forecast.isMissingNode()) {
            throw new WeatherException("高德天气响应中没有预报数据");
        }
        List<ForecastDay> allDays = new ArrayList<>();
        for (JsonNode day : forecast.path("casts")) {
            allDays.add(new ForecastDay(
                    requiredText(day, "date"),
                    requiredText(day, "week"),
                    requiredText(day, "dayweather"),
                    requiredText(day, "nightweather"),
                    requiredText(day, "daytemp"),
                    requiredText(day, "nighttemp"),
                    requiredText(day, "daywind"),
                    requiredText(day, "nightwind"),
                    requiredText(day, "daypower"),
                    requiredText(day, "nightpower")));
        }
        List<ForecastDay> selected = selectForecastDays(allDays, period);
        return new WeatherReport(
                textOrDefault(forecast, "city", location.name()),
                requiredText(forecast, "reporttime"),
                period,
                null,
                selected);
    }

    List<ForecastDay> selectForecastDays(List<ForecastDay> days, WeatherPeriod period) {
        if (period == WeatherPeriod.THREE_DAYS) {
            if (days.size() < 3) {
                throw new WeatherException("高德天气没有返回完整的三日预报");
            }
            return List.copyOf(days.subList(0, 3));
        }
        int index = switch (period) {
            case TODAY -> 0;
            case TOMORROW -> 1;
            case DAY_AFTER_TOMORROW -> 2;
            case CURRENT, THREE_DAYS -> 0;
        };
        if (days.size() <= index) {
            throw new WeatherException("高德天气没有返回所需日期的预报");
        }
        return List.of(days.get(index));
    }

    JsonNode getJson(String pathAndQuery) {
        HttpRequest request = HttpRequest.newBuilder(properties.endpoint(pathAndQuery))
                .timeout(properties.timeout())
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new WeatherException("高德接口请求失败，HTTP 状态码：" + response.statusCode());
            }
            return objectMapper.readTree(response.body());
        } catch (HttpTimeoutException exception) {
            throw new WeatherException("高德天气接口请求超时", exception);
        } catch (JsonProcessingException exception) {
            throw new WeatherException("高德天气接口返回了无法解析的数据", exception);
        } catch (IOException exception) {
            throw new WeatherException("无法连接高德天气接口", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new WeatherException("高德天气接口请求被中断", exception);
        }
    }

    private void ensureSuccess(JsonNode response, String message) {
        if (!"1".equals(response.path("status").asText())) {
            String info = response.path("info").asText();
            throw new WeatherException(info.isBlank() ? message : message + "：" + info);
        }
    }

    private String requiredText(JsonNode node, String name) {
        String value = node.path(name).asText();
        if (value.isBlank()) {
            throw new WeatherException("高德天气响应缺少字段：" + name);
        }
        return value;
    }

    private String textOrDefault(JsonNode node, String name, String defaultValue) {
        String value = node.path(name).asText();
        return value.isBlank() ? defaultValue : value;
    }

    private String mostSpecificPart(String location) {
        Matcher matcher = MOST_SPECIFIC_DISTRICT.matcher(location);
        return matcher.find() ? matcher.group(1) : location;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    record ResolvedLocation(String name, String adcode) {
    }
}
