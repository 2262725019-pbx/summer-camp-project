package com.summercamp.project.intent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.summercamp.project.weather.WeatherPeriod;
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
 * 天气查询待确认状态（用户问天气但未给出城市）。5 分钟 TTL；
 * 变更即写 JSON 文件，重启自动加载恢复，用户重启后补发城市仍可继续查询。
 */
@Component
public class PendingWeatherRequestStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(PendingWeatherRequestStore.class);
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Path filePath;

    public PendingWeatherRequestStore() {
        this(Clock.systemUTC(), null);
    }

    PendingWeatherRequestStore(Clock clock) {
        this(clock, null);
    }

    @Autowired
    public PendingWeatherRequestStore(@Value("${bot.pending-weather-file:runtime/pending-weather.json}") String filePath) {
        this(Clock.systemUTC(), filePath);
    }

    PendingWeatherRequestStore(Clock clock, String filePath) {
        this.clock = clock;
        this.filePath = filePath == null || filePath.isBlank() ? null : Path.of(filePath);
        load();
    }

    public void remember(String userId, WeatherPeriod period) {
        entries.put(userId, new Entry(period, clock.instant().plus(TTL)));
        save();
    }

    public Optional<WeatherPeriod> consume(String userId) {
        Entry entry = entries.remove(userId);
        if (entry == null) {
            return Optional.empty();
        }
        save();
        if (!entry.expiresAt().isAfter(clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(entry.period());
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
                LOGGER.warn("待确认状态文件格式异常，忽略：{}", filePath);
                return;
            }
            for (JsonNode node : root) {
                String userId = node.path("userId").asText();
                String periodName = node.path("period").asText();
                if (userId.isBlank() || periodName.isBlank()) {
                    continue;
                }
                try {
                    Instant expiresAt = Instant.parse(node.path("expiresAt").asText());
                    if (expiresAt.isAfter(clock.instant())) {
                        entries.put(userId, new Entry(WeatherPeriod.valueOf(periodName), expiresAt));
                    }
                } catch (IllegalArgumentException ignored) {
                    // 忽略未知周期或非法时间
                }
            }
        } catch (IOException exception) {
            LOGGER.warn("读取待确认状态文件失败：{}", exception.getMessage());
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
                node.put("period", entry.getValue().period().name());
                node.put("expiresAt", entry.getValue().expiresAt().toString());
            }
            Files.writeString(filePath, MAPPER.writeValueAsString(array));
        } catch (IOException exception) {
            LOGGER.warn("写入待确认状态文件失败：{}", exception.getMessage());
        }
    }

    private record Entry(WeatherPeriod period, Instant expiresAt) {
    }
}
