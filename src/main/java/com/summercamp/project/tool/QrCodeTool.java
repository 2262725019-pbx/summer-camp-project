package com.summercamp.project.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.qrcode.QRCodeWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.springframework.stereotype.Component;

@Component
public class QrCodeTool implements BotTool {

    private static final int DEFAULT_SIZE = 360;
    private static final int MAX_TEXT_CHARACTERS = 2_000;

    private final ToolDefinition definition;

    public QrCodeTool(ObjectMapper objectMapper) {
        ObjectNode schema = objectMapper.createObjectNode().put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("text")
                .put("type", "string")
                .put("description", "二维码承载的文字或网址")
                .put("minLength", 1)
                .put("maxLength", MAX_TEXT_CHARACTERS);
        properties.putObject("size")
                .put("type", "integer")
                .put("description", "二维码边长像素，默认 360")
                .put("minimum", 200)
                .put("maximum", 1_000);
        schema.putArray("required").add("text");
        schema.put("additionalProperties", false);
        definition = new ToolDefinition(
                "generate_qr_code",
                "把文本或网址生成二维码图片。用户要求生成二维码或可扫码内容时使用。",
                schema);
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public ToolResult execute(JsonNode arguments, ToolContext context) {
        String text = arguments.path("text").asText().strip();
        int size = arguments.path("size").asInt(DEFAULT_SIZE);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size);
            MatrixToImageWriter.writeToStream(matrix, "PNG", output);
            return ToolResult.image(output.toByteArray(), "qrcode.png", "二维码已生成。");
        } catch (WriterException | IOException exception) {
            throw new ToolExecutionException("二维码生成失败：" + exception.getMessage());
        }
    }
}
