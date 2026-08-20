package com.summercamp.project.wechat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.exception.ILinkException;
import com.github.wechat.ilink.sdk.core.exception.MediaUploadException;
import com.summercamp.project.config.BotProperties;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ILinkWechatGatewayTest {

    private static final byte[] MEDIA = {1, 2, 3};

    @Test
    void sendTextNormalizesSdkRuntimeExceptionToIOException() throws Exception {
        ILinkClient client = mock(ILinkClient.class);
        ILinkException cause = new ILinkException("sdk failure");
        doThrow(cause).when(client).sendText("user", "reply");

        IOException thrown = assertThrows(IOException.class,
                () -> gateway(client, "file").sendText("user", "reply"));

        assertEquals("微信消息发送失败", thrown.getMessage());
        assertSame(cause, thrown.getCause());
    }

    @Test
    void sendImageNormalizesSdkRuntimeExceptionToIOException() throws Exception {
        ILinkClient client = mock(ILinkClient.class);
        ILinkException cause = new ILinkException("sdk failure");
        doThrow(cause).when(client).sendImage("user", MEDIA, "image.png", "caption");

        IOException thrown = assertThrows(IOException.class,
                () -> gateway(client, "file").sendImage("user", MEDIA, "image.png", "caption"));

        assertEquals("微信消息发送失败", thrown.getMessage());
        assertSame(cause, thrown.getCause());
    }

    @Test
    void sendVoiceFileNormalizesMediaUploadExceptionToIOException() throws Exception {
        ILinkClient client = mock(ILinkClient.class);
        MediaUploadException cause = new MediaUploadException("cdn upload failed");
        doThrow(cause).when(client).sendFile("user", MEDIA, "reply.mp3", "");

        IOException thrown = assertThrows(IOException.class,
                () -> gateway(client, "file").sendVoice(
                        "user", MEDIA, "reply.mp3", 1_000, 24_000, 7, 16, "answer"));

        assertEquals("微信消息发送失败", thrown.getMessage());
        assertSame(cause, thrown.getCause());
    }

    @Test
    void sendNativeVoiceNormalizesBaseILinkExceptionToIOException() throws Exception {
        ILinkClient client = mock(ILinkClient.class);
        ILinkException cause = new ILinkException("sdk failure");
        doThrow(cause).when(client).sendVoice(
                "user", MEDIA, "reply.pcm", 1_000, 24_000, null, 1, 16, "answer");

        IOException thrown = assertThrows(IOException.class,
                () -> gateway(client, "native").sendVoice(
                        "user", MEDIA, "reply.pcm", 1_000, 24_000, 1, 16, "answer"));

        assertEquals("微信消息发送失败", thrown.getMessage());
        assertSame(cause, thrown.getCause());
    }

    @Test
    void successfulTextImageAndVoiceSendsStillDelegateOnce() throws Exception {
        ILinkClient client = mock(ILinkClient.class);
        ILinkWechatGateway gateway = gateway(client, "file");

        gateway.sendText("user", "reply");
        gateway.sendImage("user", MEDIA, "image.png", "caption");
        gateway.sendVoice("user", MEDIA, "reply.mp3", 1_000, 24_000, 7, 16, "answer");

        verify(client).sendText("user", "reply");
        verify(client).sendImage("user", MEDIA, "image.png", "caption");
        verify(client).sendFile("user", MEDIA, "reply.mp3", "");
    }

    private static ILinkWechatGateway gateway(ILinkClient client, String voiceReplyMode) {
        BotProperties properties = new BotProperties(
                true,
                25_000_000,
                25_000_000,
                Duration.ofSeconds(30),
                voiceReplyMode,
                Path.of("qrcode.png"),
                false,
                Duration.ofSeconds(1));
        return new ILinkWechatGateway(properties, client);
    }
}
