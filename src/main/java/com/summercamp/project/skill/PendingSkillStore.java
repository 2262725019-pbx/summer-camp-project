package com.summercamp.project.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 等待用户补充资料的技能续接状态。5 分钟 TTL；变更即写 JSON 文件，重启自动加载恢复，
 * 用户重启后回复补充内容仍可继续 Skill 流程。
 */
@Component
public class PendingSkillStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(PendingSkillStore.class);
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Path filePath;

    public PendingSkillStore() {
        this(Clock.systemUTC(), null);
    }

    PendingSkillStore(Clock clock) {
        this(clock, null);
    }

    @Autowired
    public PendingSkillStore(@Value("${bot.pending-skill-file:runtime/pending-skills.json}") String filePath) {
        this(Clock.systemUTC(), filePath);
    }

    PendingSkillStore(Clock clock, String filePath) {
        this.clock = clock;
        this.filePath = filePath == null || filePath.isBlank() ? null : Path.of(filePath);
        load();
    }

    public void remember(String userId, String skillName) {
        entries.put(userId, new Entry(skillName, clock.instant().plus(TTL)));
        save();
    }

    public Optional<String> get(String userId) {
        Entry entry = entries.get(userId);
        if (entry == null) {
            return Optional.empty();
        }
        if (!entry.expiresAt().isAfter(clock.instant())) {
            entries.remove(userId, entry);
            save();
            return Optional.empty();
        }
        return Optional.of(entry.skillName());
    }

    public void clear(String userId) {
        if (entries.remove(userId) != null) {
            save();
        }
    }

    // ---------------------------------------------------------------- 持久化

    private void load() {
        if (filePath == null || !Files.exists(filePath)) {
            return;
        }
        try {
            JsonNode root = MAPPER.readTree(Files.readAllBytes(filePath));
            if (!root.isArray()) {
                LOGGER.warn("待补充状态文件格式异常，忽略：{}", filePath);
                return;
            }
            for (JsonNode node : root) {
                String userId = node.path("userId").asText();
                String skillName = node.path("skillName").asText();
                if (userId.isBlank() || skillName.isBlank()) {
                    continue;
                }
                Instant expiresAt = Instant.parse(node.path("expiresAt").asText());
                if (expiresAt.isAfter(clock.instant())) {
                    entries.put(userId, new Entry(skillName, expiresAt));
                }
            }
        } catch (IOException exception) {
            LOGGER.warn("读取待补充状态文件失败：{}", exception.getMessage());
        }
    }

    private void save() {
        if (filePath == null) {
            return;
        }
        try {
            Path parent = filePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            ArrayNode array = MAPPER.createArrayNode();
            for (Map.Entry<String, Entry> entry : entries.entrySet()) {
                ObjectNode node = array.addObject();
                node.put("userId", entry.getKey());
                node.put("skillName", entry.getValue().skillName());
                node.put("expiresAt", entry.getValue().expiresAt().toString());
            }
            Files.writeString(filePath, MAPPER.writeValueAsString(array));
        } catch (IOException exception) {
            LOGGER.warn("写入待补充状态文件失败：{}", exception.getMessage());
        }
    }

    private record Entry(String skillName, Instant expiresAt) {
    }
}
