package com.summercamp.project.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import java.io.ByteArrayInputStream;
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
    void asciiUrlRoundTripsThroughGeneratedQrCode() throws Exception {
        assertRoundTrip("https://example.com/results/4500");
    }

    @Test
    void mixedChineseAndAsciiTextRoundTripsThroughGeneratedQrCode() throws Exception {
        assertRoundTrip("夏令营测试 2026-08-20 星期四");
    }

    private void assertRoundTrip(String text) throws Exception {
        var arguments = objectMapper.createObjectNode().put("text", text);
        ToolResult.Image image = assertInstanceOf(
                ToolResult.Image.class,
                tool.execute(arguments, ToolContext.anonymous()));
        var bufferedImage = ImageIO.read(new ByteArrayInputStream(image.data()));
        BinaryBitmap bitmap = new BinaryBitmap(
                new HybridBinarizer(new BufferedImageLuminanceSource(bufferedImage)));
        Result decoded = new MultiFormatReader().decode(bitmap);

        assertEquals(text, decoded.getText());
    }
}
