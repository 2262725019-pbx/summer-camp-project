package com.summercamp.project.wechat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public interface WechatGateway extends AutoCloseable {

    void loginAndWait(Path qrCodePath) throws Exception;

    List<InboundMessage> poll() throws IOException;

    void sendText(String userId, String text) throws IOException;

    void sendImage(String userId, byte[] data, String fileName, String caption) throws IOException;

    void sendVoice(
            String userId,
            byte[] data,
            String fileName,
            int durationMillis,
            int sampleRate,
            int encodeType,
            int bitsPerSample,
            String transcript) throws IOException;

    @Override
    void close();
}
