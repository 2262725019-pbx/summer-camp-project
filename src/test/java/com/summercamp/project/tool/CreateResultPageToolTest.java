package com.summercamp.project.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summercamp.project.config.ResultPageProperties;
import com.summercamp.project.result.ResultPageService;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class CreateResultPageToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void createsAResultPageUrlForTheNextQrCodeStep() {
        ResultPageService service = new ResultPageService(
                new ResultPageProperties(
                        "http://192.168.1.20:8080", 8080, Duration.ofMinutes(30)));
        CreateResultPageTool tool = new CreateResultPageTool(objectMapper, service);

        ToolResult.Data result = assertInstanceOf(
                ToolResult.Data.class,
                tool.execute(
                        objectMapper.createObjectNode()
                                .put("expression", "125 * 36")
                                .put("result", "4500"),
                        ToolContext.anonymous()));

        assertTrue(result.content().path("url").asText()
                .startsWith("http://192.168.1.20:8080/results/"));
        assertEquals(
                "结果页已创建，请把 url 传给 generate_qr_code",
                result.content().path("message").asText());
    }
}
