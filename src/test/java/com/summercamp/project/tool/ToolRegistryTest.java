package com.summercamp.project.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summercamp.project.weather.CurrentWeather;
import com.summercamp.project.weather.ForecastDay;
import com.summercamp.project.weather.WeatherClient;
import com.summercamp.project.weather.WeatherPeriod;
import com.summercamp.project.weather.WeatherReport;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ToolRegistryTest {

    private ObjectMapper objectMapper;
    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        WeatherClient weatherClient = (location, period) -> weather(location, period);
        registry = new ToolRegistry(
                List.of(
                        new WeatherTool(weatherClient, objectMapper),
                        new CalculatorTool(objectMapper)),
                objectMapper);
    }

    @Test
    void exposesTwoStrictJsonSchemas() {
        assertEquals(List.of("calculate", "get_weather"), registry.definitions().stream()
                .map(ToolDefinition::name)
                .toList());

        ToolDefinition weather = registry.definitions().get(1);
        assertEquals("object", weather.parameters().path("type").asText());
        assertEquals(List.of("location", "period"), weather.parameters().path("required")
                .valueStream().map(JsonNode::asText).toList());
        assertFalse(weather.parameters().path("additionalProperties").asBoolean(true));
        assertEquals(5, weather.parameters().path("properties")
                .path("period").path("enum").size());
        assertTrue(registry.isParallelSafe("calculate"));
        assertTrue(registry.isParallelSafe("get_weather"));
        assertFalse(registry.isParallelSafe("unknown_tool"));
    }

    @Test
    void calculatesAndRejectsDivisionByZero() throws Exception {
        JsonNode success = invoke("calculate",
                "{\"left\":125,\"operator\":\"MULTIPLY\",\"right\":36}");
        JsonNode failure = invoke("calculate",
                "{\"left\":10,\"operator\":\"DIVIDE\",\"right\":0}");

        assertTrue(success.path("success").asBoolean());
        assertEquals("4500", success.path("result").path("value").asText());
        assertFalse(failure.path("success").asBoolean());
        assertTrue(failure.path("error").asText().contains("除数不能为 0"));
    }

    @Test
    void returnsStructuredWeatherData() throws Exception {
        JsonNode result = invoke("get_weather",
                "{\"location\":\"江西省宜春市袁州区\",\"period\":\"TOMORROW\"}");

        assertTrue(result.path("success").asBoolean());
        assertEquals("TOMORROW", result.path("result").path("data").path("period").asText());
        assertTrue(result.path("result").path("formatted_text").asText().contains("天气预报"));
    }

    @Test
    void rejectsUnknownToolAndInvalidJson() throws Exception {
        JsonNode unknown = invoke("run_shell", "{}");
        JsonNode invalid = invoke("calculate", "not-json");
        JsonNode extraArgument = invoke("calculate",
                "{\"left\":1,\"operator\":\"ADD\",\"right\":2,\"script\":\"danger\"}");

        assertFalse(unknown.path("success").asBoolean());
        assertTrue(unknown.path("error").asText().contains("未知工具"));
        assertFalse(invalid.path("success").asBoolean());
        assertTrue(invalid.path("error").asText().contains("有效 JSON"));
        assertFalse(extraArgument.path("success").asBoolean());
        assertTrue(extraArgument.path("error").asText().contains("不允许的工具参数"));
    }

    @Test
    void requestPolicyRejectsRegisteredButUnauthorizedTool() throws Exception {
        ToolRegistry.Invocation invocation = registry.invoke(
                "calculate",
                "{\"left\":1,\"operator\":\"ADD\",\"right\":2}",
                ToolContext.anonymous(),
                ToolAccessPolicy.allowOnly(java.util.Set.of("get_weather")));
        JsonNode result = objectMapper.readTree(invocation.modelContent());

        assertFalse(invocation.success());
        assertFalse(result.path("success").asBoolean());
        assertTrue(result.path("error").asText().contains("不允许调用工具"));
    }

    private JsonNode invoke(String name, String arguments) throws Exception {
        return objectMapper.readTree(
                registry.invoke(name, arguments, ToolContext.anonymous()).modelContent());
    }

    private WeatherReport weather(String location, WeatherPeriod period) {
        if (period == WeatherPeriod.CURRENT) {
            return new WeatherReport(
                    location,
                    "2026-08-19 15:00:00",
                    period,
                    new CurrentWeather("晴", "31", "52", "东", "2"),
                    List.of());
        }
        return new WeatherReport(
                location,
                "2026-08-19 15:00:00",
                period,
                null,
                List.of(new ForecastDay(
                        "2026-08-20", "4", "晴", "多云", "33", "25", "东", "东", "2", "1")));
    }
}
