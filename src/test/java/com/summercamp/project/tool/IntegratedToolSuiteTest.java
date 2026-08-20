package com.summercamp.project.tool;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class IntegratedToolSuiteTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void calculatorSupportsExpressionsAndLegacyExactOperations() throws Exception {
        ToolRegistry registry = new ToolRegistry(
                List.of(new CalculatorTool(objectMapper)), objectMapper);

        JsonNode expression = invokeJson(registry,
                "calculate", "{\"expression\":\"12.5 * 3 + sqrt(9)\"}", ToolContext.anonymous());
        JsonNode exact = invokeJson(registry,
                "calculate",
                "{\"left\":0.1,\"operator\":\"ADD\",\"right\":0.2}",
                ToolContext.anonymous());

        assertEquals("40.5", expression.path("result").path("value").asText());
        assertEquals("0.3", exact.path("result").path("value").asText());
    }

    @Test
    void todoToolsKeepUsersIsolatedAndSupportAChainedFlow() {
        TodoService service = new TodoService();
        ToolRegistry registry = new ToolRegistry(
                List.of(
                        new AddTodoTool(service, objectMapper),
                        new ListTodosTool(service, objectMapper),
                        new CompleteTodoTool(service, objectMapper)),
                objectMapper);
        ToolContext userA = new ToolContext("user-a", "先添加再查看待办");
        ToolContext userB = new ToolContext("user-b", "查看待办");

        registry.invoke("add_todo", "{\"item\":\"完成夏令营日报\"}", userA);
        ToolRegistry.Invocation listed = registry.invoke("list_todos", "{}", userA);
        ToolRegistry.Invocation otherUser = registry.invoke("list_todos", "{}", userB);
        registry.invoke("complete_todo", "{\"index\":1}", userA);

        assertTrue(((ToolResult.Text) listed.result()).content().contains("完成夏令营日报"));
        assertEquals("你目前没有待办事项。", ((ToolResult.Text) otherUser.result()).content());
        assertTrue(service.list("user-a").isEmpty());
    }

    @Test
    void dateTimeToolUsesRequestedTimezone() {
        DateTimeTool tool = new DateTimeTool(
                objectMapper,
                Clock.fixed(Instant.parse("2026-08-20T04:00:00Z"), ZoneOffset.UTC));

        ToolResult.Data result = assertInstanceOf(
                ToolResult.Data.class,
                tool.execute(
                        objectMapper.createObjectNode().put("timezone", "Asia/Shanghai"),
                        ToolContext.anonymous()));

        assertEquals("2026-08-20", result.content().path("date").asText());
        assertEquals("12:00", result.content().path("time").asText());
        assertEquals("Asia/Shanghai", result.content().path("timezone").asText());
    }

    @Test
    void qrCodeToolProducesRealPngBytes() {
        QrCodeTool tool = new QrCodeTool(objectMapper);

        ToolResult.Image image = assertInstanceOf(
                ToolResult.Image.class,
                tool.execute(
                        objectMapper.createObjectNode().put("text", "https://example.com"),
                        ToolContext.anonymous()));

        assertEquals("qrcode.png", image.fileName());
        assertTrue(image.data().length > 100);
        assertArrayEquals(
                new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47},
                java.util.Arrays.copyOf(image.data(), 4));
    }

    @Test
    void registryRejectsDuplicateNamesAndWrongSchemaTypes() throws Exception {
        CalculatorTool first = new CalculatorTool(objectMapper);
        assertThrows(
                IllegalStateException.class,
                () -> new ToolRegistry(List.of(first, first), objectMapper));

        ToolRegistry registry = new ToolRegistry(List.of(first), objectMapper);
        JsonNode result = invokeJson(
                registry, "calculate", "{\"expression\":123}", ToolContext.anonymous());

        assertTrue(!result.path("success").asBoolean());
        assertTrue(result.path("error").asText().contains("类型"));
    }

    private JsonNode invokeJson(
            ToolRegistry registry,
            String name,
            String arguments,
            ToolContext context) throws Exception {
        return objectMapper.readTree(registry.invoke(name, arguments, context).modelContent());
    }
}
