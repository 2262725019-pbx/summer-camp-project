package com.summercamp.project.message;

import com.summercamp.project.config.AiChatProperties;
import com.summercamp.project.config.BotProperties;
import com.summercamp.project.wechat.InboundMessage;
import com.summercamp.project.wechat.WechatGateway;
import com.summercamp.project.wechat.WechatSessionExpiredException;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "bot", name = "enabled", havingValue = "true")
public class WechatBotRunner implements ApplicationRunner, DisposableBean {

    private static final Logger LOGGER = LoggerFactory.getLogger(WechatBotRunner.class);

    private final WechatGateway gateway;
    private final MessageProcessor processor;
    private final BotProperties botProperties;
    private final AiChatProperties aiProperties;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ExecutorService pollingExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "wechat-polling");
        thread.setDaemon(false);
        return thread;
    });
    private final ThreadPoolExecutor messageExecutor = new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(100),
            runnable -> {
                Thread thread = new Thread(runnable, "wechat-message-worker");
                thread.setDaemon(false);
                return thread;
            },
            new ThreadPoolExecutor.CallerRunsPolicy());

    public WechatBotRunner(
            WechatGateway gateway,
            MessageProcessor processor,
            BotProperties botProperties,
            AiChatProperties aiProperties) {
        this.gateway = gateway;
        this.processor = processor;
        this.botProperties = botProperties;
        this.aiProperties = aiProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        botProperties.validate();
        aiProperties.validate();
        running.set(true);
        pollingExecutor.submit(this::pollLoop);
    }

    private void pollLoop() {
        boolean loginRequired = true;
        while (running.get()) {
            try {
                if (loginRequired) {
                    gateway.loginAndWait(botProperties.qrCodePath());
                    loginRequired = false;
                }
                List<InboundMessage> messages = gateway.poll();
                for (InboundMessage message : messages) {
                    messageExecutor.execute(() -> processor.process(message));
                }
            } catch (WechatSessionExpiredException exception) {
                LOGGER.warn("微信登录已失效，将生成新的二维码等待重新登录");
                loginRequired = true;
                pause();
            } catch (IOException exception) {
                LOGGER.warn("微信长轮询暂时失败，将自动重试：{}", exception.getMessage());
                pause();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception exception) {
                LOGGER.error("微信机器人运行失败，将重新开始登录流程", exception);
                loginRequired = true;
                pause();
            }
        }
    }

    private void pause() {
        try {
            Thread.sleep(botProperties.pollRetryDelay().toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void destroy() {
        running.set(false);
        pollingExecutor.shutdownNow();
        messageExecutor.shutdownNow();
        gateway.close();
    }
}
