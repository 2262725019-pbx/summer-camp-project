package com.summercamp.project.wechat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.summercamp.project.config.BotProperties;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ILinkWechatGatewayTest {

    @Test
    void acceptsInjectedClientWithoutConnectingToWechat() {
        BotProperties properties = new BotProperties(
                false,
                10 * 1024 * 1024,
                25 * 1024 * 1024,
                Duration.ofSeconds(30),
                "file",
                Path.of("runtime", "wechat-login-qr.png"),
                false,
                Duration.ofSeconds(2),
                4);
        ILinkClient client = mock(ILinkClient.class);

        ILinkWechatGateway gateway = new ILinkWechatGateway(properties, client);
        gateway.close();

        verify(client).close();
    }
}
