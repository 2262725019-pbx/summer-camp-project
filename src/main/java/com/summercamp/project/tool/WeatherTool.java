package com.summercamp.project.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.summercamp.project.weather.WeatherClient;
import com.summercamp.project.weather.WeatherPeriod;
import com.summercamp.project.weather.WeatherReport;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class WeatherTool implements BotTool {

    private static final int MAX_LOCATION_CHARACTERS = 100;

    private final WeatherClient weatherClient;
    private final ObjectMapper objectMapper;
    private final ToolDefinition definition;

    public WeatherTool(WeatherClient weatherClient, ObjectMapper objectMapper) {
        this.weatherClient = weatherClient;
        this.objectMapper = objectMapper;
        this.definition = new ToolDefinition(
                "get_weather",
                "查询中国城市或区县的真实实时天气及天气预报。涉及天气、温度、降雨或带伞问题时必须使用。",
                schema(objectMapper));
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public boolean parallelSafe() {
        return true;
    }

    @Override
    public ToolResult execute(JsonNode arguments, ToolContext context) {
        String location = arguments.path("location").asText().strip();
        if (location.isBlank()) {
            throw new ToolExecutionException("缺少天气查询地点");
        }
        if (location.length() > MAX_LOCATION_CHARACTERS) {
            throw new ToolExecutionException("天气查询地点过长");
        }
        String periodText = arguments.path("period").asText().strip();
        if (periodText.isBlank()) {
            throw new ToolExecutionException("缺少天气查询时间范围");
        }
        WeatherPeriod period;
        try {
            period = WeatherPeriod.valueOf(periodText.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new ToolExecutionException("不支持的天气查询时间范围：" + periodText);
        }
        WeatherReport report = weatherClient.query(location, period);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("formatted_text", report.formatChinese());
        response.set("data", objectMapper.valueToTree(report));
        return ToolResult.data(response);
    }

    private static ObjectNode schema(ObjectMapper objectMapper) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("location")
                .put("type", "string")
                .put("description", "中国城市或区县名称，建议使用完整行政区划名称，如“北京市”、“上海市浦东新区”等")
                .put("minLength", 1)
                .put("maxLength", MAX_LOCATION_CHARACTERS);
        properties.putObject("period")
                .put("type", "string")
                .put("description", "查询时间范围")
                .putArray("enum")
                .add("CURRENT")
                .add("TODAY")
                .add("TOMORROW")
                .add("DAY_AFTER_TOMORROW")
                .add("THREE_DAYS");
        schema.putArray("required").add("location").add("period");
        schema.put("additionalProperties", false);
        return schema;
    }
}
