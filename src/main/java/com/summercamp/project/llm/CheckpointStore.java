package com.summercamp.project.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 多轮任务断点存储：保存模型工具链中途失败时的请求快照（payload + 轮次），
 * 用户回复"继续"后从断点续跑，避免整条任务重来或重复执行已完成的工具。
 * 变更即写 JSON 文件，重启自动加载恢复；断点 30 分钟内有效。
 */
@Component
public class CheckpointStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(CheckpointStore.class);
    private static final Duration TTL = Duration.ofMinutes(30);

    /** 断点快照：模型请求 payload、已执行到的轮次、是否带图片，以及模型名。 */
    public record TaskCheckpoint(
            String userId,
            String model,
            ObjectNode payload,
            int round,
            boolean withImages,
            long createdAtMillis) {
    }

    private final ObjectMapper objectMapper;
    private final Path filePath;
    private final Clock clock;
    private final Map<String, TaskCheckpoint> checkpoints = new ConcurrentHashMap<>();

    @Autowired
    public CheckpointStore(
            ObjectMapper objectMapper,
            @Value("${bot.checkpoint-file:runtime/checkpoints.json}") String filePath) {
        this(objectMapper, Path.of(filePath), Clock.systemUTC());
    }

    CheckpointStore(ObjectMapper objectMapper, Path filePath, Clock clock) {
        this.objectMapper = objectMapper;
        this.filePath = filePath;
        this.clock = clock;
        load();
    }

    public void save(String userId, String model, ObjectNode payload, int round, boolean withImages) {
        if (userId == null || userId.isBlank() || payload == null) {
            return;
        }
        checkpoints.put(userId, new TaskCheckpoint(
                userId, model, payload.deepCopy(), round, withImages, clock.millis()));
        saveFile();
    }

    public Optional<TaskCheckpoint> load(String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        TaskCheckpoint checkpoint = checkpoints.get(userId);
        if (checkpoint == null) {
            return Optional.empty();
        }
        if (checkpoint.createdAtMillis() + TTL.toMillis() < clock.millis()) {
            checkpoints.remove(userId, checkpoint);
            return Optional.empty();
        }
        return Optional.of(checkpoint);
    }

    public void clear(String userId) {
        if (userId != null && checkpoints.remove(userId) != null) {
            saveFile();
        }
    }

    // ---------------------------------------------------------------- 持久化

    private void load() {
        if (filePath == null || !Files.exists(filePath)) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(Files.readAllBytes(filePath));
            if (!root.isArray()) {
                LOGGER.warn("断点文件格式异常，忽略：{}", filePath);
                return;
            }
            for (JsonNode node : root) {
                String userId = node.path("userId").asText();
                if (userId.isBlank()) {
                    continue;
                }
                JsonNode payload = node.path("payload");
                if (!payload.isObject()) {
                    continue;
                }
                checkpoints.put(userId, new TaskCheckpoint(
                        userId,
                        node.path("model").asText(),
                        (ObjectNode) payload,
                        node.path("round").asInt(0),
                        node.path("withImages").asBoolean(false),
                        node.path("createdAtMillis").asLong(clock.millis())));
            }
        } catch (IOException exception) {
            LOGGER.warn("读取断点文件失败：{}", exception.getMessage());
        }
    }

    private void saveFile() {
        if (filePath == null) {
            return;
        }
        try {
            Path parent = filePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            ArrayNode array = objectMapper.createArrayNode();
            for (TaskCheckpoint checkpoint : checkpoints.values()) {
                ObjectNode node = array.addObject();
                node.put("userId", checkpoint.userId());
                node.put("model", checkpoint.model());
                node.put("round", checkpoint.round());
                node.put("withImages", checkpoint.withImages());
                node.put("createdAtMillis", checkpoint.createdAtMillis());
                node.set("payload", checkpoint.payload());
            }
            Files.writeString(filePath, objectMapper.writeValueAsString(array));
        } catch (IOException exception) {
            LOGGER.warn("写入断点文件失败：{}", exception.getMessage());
        }
    }
}
