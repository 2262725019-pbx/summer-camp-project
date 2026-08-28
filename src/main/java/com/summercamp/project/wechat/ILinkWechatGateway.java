package com.summercamp.project.wechat;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.exception.SessionExpiredException;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.VoiceItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.summercamp.project.config.BotProperties;
import com.summercamp.project.llm.ImageInput;
import com.summercamp.project.speech.VoiceInput;
import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

@Component
public class ILinkWechatGateway implements WechatGateway, DisposableBean {

    private static final Logger LOGGER = LoggerFactory.getLogger(ILinkWechatGateway.class);

    private final ILinkClient client;
    private final BotProperties properties;
    private final AtomicBoolean closed = new AtomicBoolean();
    /** 按用户串行发送：回执（message-ack 线程）与结果（消息处理线程）对同一用户按调用顺序送达。 */
    private final ConcurrentHashMap<String, ReentrantLock> sendLocks = new ConcurrentHashMap<>();
    private Path activeQrCodePath;

    @Autowired
    public ILinkWechatGateway(BotProperties properties) {
        this(properties, createClient());
    }

    ILinkWechatGateway(BotProperties properties, ILinkClient client) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.client = Objects.requireNonNull(client, "client");
    }

    private static ILinkClient createClient() {
        ILinkConfig config = ILinkConfig.builder()
                .heartbeatEnabled(false)
                .build();
        return ILinkClient.builder().config(config).build();
    }

    @Override
    public void loginAndWait(Path qrCodePath) throws Exception {
        String qrCodeContent = client.executeLogin();
        writeQrCode(qrCodeContent, qrCodePath);
        activeQrCodePath = qrCodePath;
        LOGGER.info("微信登录二维码已生成：{}，请在 3 分钟内扫码确认", qrCodePath.toAbsolutePath());
        if (properties.qrCodeAutoOpen()) {
            openQrCode(qrCodePath);
        }
        try {
            client.getLoginFuture().get();
            LOGGER.info("微信 iLink 登录成功");
        } catch (ExecutionException exception) {
            throw new IllegalStateException("微信扫码登录失败", exception.getCause());
        } finally {
            deleteQrCode();
        }
    }

    @Override
    public List<InboundMessage> poll() throws IOException {
        try {
            List<WeixinMessage> messages = client.getUpdates();
            if (messages == null || messages.isEmpty()) {
                return List.of();
            }
            List<InboundMessage> result = new ArrayList<>(messages.size());
            for (WeixinMessage message : messages) {
                InboundMessage converted = convert(message);
                if (converted != null) {
                    result.add(converted);
                }
            }
            return result;
        } catch (SessionExpiredException exception) {
            throw new WechatSessionExpiredException("微信登录状态已失效", exception);
        }
    }

    @Override
    public void sendText(String userId, String text) throws IOException {
        withSendLock(userId, () -> client.sendText(userId, text));
    }

    @Override
    public void sendImage(String userId, byte[] data, String fileName, String caption)
            throws IOException {
        withSendLock(userId, () -> client.sendImage(userId, data, fileName, caption));
    }

    @Override
    public void sendVoice(
            String userId,
            byte[] data,
            String fileName,
            int durationMillis,
            int sampleRate,
            int encodeType,
            int bitsPerSample,
            String transcript) throws IOException {
        if (properties.sendVoiceAsFile()) {
            withSendLock(userId, () ->
                    client.sendFile(userId, data, playableAudioFileName(fileName, encodeType), ""));
            LOGGER.info("iLink 原生语音气泡当前投递不稳定，已将回复作为可播放音频文件发送");
            return;
        }
        withSendLock(userId, () -> client.sendVoice(
                userId,
                data,
                fileName,
                durationMillis,
                sampleRate,
                null,
                encodeType,
                bitsPerSample,
                transcript));
        LOGGER.warn("已向 iLink 提交原生语音气泡；接口成功不代表微信客户端一定完成投递");
    }

    private void withSendLock(String userId, IoAction action) throws IOException {
        ReentrantLock lock = sendLocks.computeIfAbsent(userId, ignored -> new ReentrantLock());
        lock.lock();
        try {
            action.run();
        } finally {
            lock.unlock();
        }
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws IOException;
    }

    private String playableAudioFileName(String fileName, int encodeType) {
        if (fileName != null && !fileName.isBlank()) {
            return fileName;
        }
        return encodeType == 7 ? "AI语音回复.mp3" : "AI语音回复.audio";
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        deleteQrCode();
        client.close();
    }

    @Override
    public void destroy() {
        close();
    }

    private InboundMessage convert(WeixinMessage message) throws IOException {
        if (message == null || message.getFrom_user_id() == null) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        List<ImageInput> images = new ArrayList<>();
        List<VoiceInput> voices = new ArrayList<>();
        boolean imageTooLarge = false;
        boolean voiceTooLarge = false;
        boolean unsupportedMedia = false;
        List<MessageItem> items = message.getItem_list();
        if (items != null) {
            for (MessageItem item : items) {
                if (item == null) {
                    continue;
                }
                if (item.getType() == 1 && item.getText_item() != null
                        && item.getText_item().getText() != null) {
                    if (!text.isEmpty()) {
                        text.append('\n');
                    }
                    text.append(item.getText_item().getText());
                } else if (item.getType() == 2 && item.getImage_item() != null) {
                    byte[] data;
                    if (item.getImage_item().getMedia() != null) {
                        data = client.downloadImageFromMessageItem(item);
                    } else if (item.getImage_item().getThumb_media() != null) {
                        data = client.downloadImageThumbFromMessageItem(item);
                    } else {
                        unsupportedMedia = true;
                        continue;
                    }
                    if (data.length > properties.imageMaxBytes()) {
                        imageTooLarge = true;
                    } else {
                        images.add(new ImageInput(data, detectImageMediaType(data)));
                    }
                } else if (item.getType() == 3 && item.getVoice_item() != null) {
                    VoiceItem voice = item.getVoice_item();
                    byte[] data = voice.getMedia() == null
                            ? new byte[0]
                            : client.downloadVoiceFromMessageItem(item);
                    int playtime = intOrDefault(voice.getPlaytime(), 0);
                    if (data.length > properties.voiceMaxBytes()
                            || playtime > properties.voiceMaxDuration().toMillis()) {
                        voiceTooLarge = true;
                    } else if (data.length > 0 || (voice.getText() != null && !voice.getText().isBlank())) {
                        voices.add(new VoiceInput(
                                data,
                                voice.getText(),
                                intOrDefault(voice.getEncode_type(), 6),
                                intOrDefault(voice.getBits_per_sample(), 16),
                                intOrDefault(voice.getSample_rate(), 24_000),
                                playtime));
                    } else {
                        unsupportedMedia = true;
                    }
                } else if (item.getType() >= 4) {
                    unsupportedMedia = true;
                }
            }
        }
        return new InboundMessage(
                message.getMessage_id() == null ? null : message.getMessage_id().toString(),
                message.getFrom_user_id(),
                text.toString(),
                images,
                voices,
                imageTooLarge,
                voiceTooLarge,
                unsupportedMedia);
    }

    private int intOrDefault(Integer value, int defaultValue) {
        return value == null ? defaultValue : value;
    }

    private void writeQrCode(String content, Path path) throws IOException {
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 420, 420);
            MatrixToImageWriter.writeToPath(matrix, "PNG", path.toAbsolutePath());
        } catch (WriterException exception) {
            throw new IOException("无法生成微信登录二维码", exception);
        }
    }

    private void openQrCode(Path path) {
        Path absolutePath = path.toAbsolutePath();
        if (!Desktop.isDesktopSupported()) {
            LOGGER.warn("当前运行环境无法自动打开二维码，请手动打开：{}", absolutePath);
            return;
        }
        try {
            Desktop.getDesktop().open(absolutePath.toFile());
            LOGGER.info("已使用系统图片查看器自动打开微信登录二维码");
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            LOGGER.warn("自动打开二维码失败，请手动打开：{}，原因：{}",
                    absolutePath,
                    exception.getMessage());
        }
    }

    private String detectImageMediaType(byte[] data) {
        if (startsWith(data, 0x89, 0x50, 0x4E, 0x47)) {
            return "image/png";
        }
        if (startsWith(data, 0xFF, 0xD8, 0xFF)) {
            return "image/jpeg";
        }
        if (startsWith(data, 0x47, 0x49, 0x46, 0x38)) {
            return "image/gif";
        }
        if (data.length >= 12
                && startsWith(data, 0x52, 0x49, 0x46, 0x46)
                && data[8] == 0x57 && data[9] == 0x45 && data[10] == 0x42 && data[11] == 0x50) {
            return "image/webp";
        }
        return "image/jpeg";
    }

    private boolean startsWith(byte[] data, int... signature) {
        if (data.length < signature.length) {
            return false;
        }
        for (int index = 0; index < signature.length; index++) {
            if ((data[index] & 0xFF) != signature[index]) {
                return false;
            }
        }
        return true;
    }

    private void deleteQrCode() {
        if (activeQrCodePath == null) {
            return;
        }
        try {
            Files.deleteIfExists(activeQrCodePath.toAbsolutePath());
        } catch (IOException exception) {
            LOGGER.warn("无法删除已失效的微信登录二维码：{}", activeQrCodePath.toAbsolutePath());
        } finally {
            activeQrCodePath = null;
        }
    }
}
