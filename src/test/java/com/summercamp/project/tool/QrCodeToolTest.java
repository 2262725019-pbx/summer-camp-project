package com.summercamp.project.tool;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class QrCodeToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final QrCodeTool tool = new QrCodeTool(objectMapper);

    @Test
    void chineseTextRoundTripsThroughGeneratedQrCode() throws Exception {
        assertRoundTrip("2026年8月20日 16时25分14秒 星期四");
    }

    @Test
    void asciiUrlStillRoundTripsThroughGeneratedQrCode() throws Exception {
        assertRoundTrip("https://example.com");
    }

    @Test
    void mixedChineseAndAsciiTextRoundTripsThroughGeneratedQrCode() throws Exception {
        assertRoundTrip("夏令营测试 2026-08-20 星期四");
    }

    private void assertRoundTrip(String expected) throws Exception {
        ToolResult.Image image = assertInstanceOf(
                ToolResult.Image.class,
                tool.execute(
                        objectMapper.createObjectNode().put("text", expected),
                        ToolContext.anonymous()));

        var bufferedImage = ImageIO.read(new ByteArrayInputStream(image.data()));
        BinaryBitmap bitmap = new BinaryBitmap(
                new HybridBinarizer(new BufferedImageLuminanceSource(bufferedImage)));
        String decoded = new MultiFormatReader().decode(bitmap).getText();

        assertEquals(expected, decoded);
        assertArrayEquals(
                expected.getBytes(StandardCharsets.UTF_8),
                decoded.getBytes(StandardCharsets.UTF_8));
    }
}
