package com.summercamp.project.agent.store;

import com.summercamp.project.config.AgentPersistenceProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "agent.persistence.enabled", havingValue = "true", matchIfMissing = true)
public class AgentStateDatabase {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentStateDatabase.class);

    private final String jdbcUrl;

    public AgentStateDatabase(AgentPersistenceProperties properties) {
        Path database = properties.path();
        try {
            Path parent = database.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("无法创建 Agent 状态目录", exception);
        }
        jdbcUrl = "jdbc:sqlite:" + database.toString().replace('\\', '/');
        initialize();
        LOGGER.info("Agent SQLite 状态库已启用：{}", database.getFileName());
    }

    public synchronized void saveRun(String userId, String payload, Instant expiresAt) {
        upsert("""
                INSERT INTO agent_runs(user_id, payload, expires_at) VALUES (?, ?, ?)
                ON CONFLICT(user_id) DO UPDATE SET payload=excluded.payload, expires_at=excluded.expires_at
                """, userId, payload, expiresAt.toEpochMilli());
    }

    public synchronized Optional<String> loadRun(String userId, Instant now) {
        return loadPayload("agent_runs", userId, now);
    }

    public synchronized void deleteRun(String userId) {
        delete("agent_runs", userId);
    }

    public synchronized void saveCompletedPlan(String userId, String payload, Instant expiresAt) {
        upsert("""
                INSERT INTO completed_health_plans(user_id, payload, expires_at) VALUES (?, ?, ?)
                ON CONFLICT(user_id) DO UPDATE SET payload=excluded.payload, expires_at=excluded.expires_at
                """, userId, payload, expiresAt.toEpochMilli());
    }

    public synchronized Optional<String> loadCompletedPlan(String userId, Instant now) {
        return loadPayload("completed_health_plans", userId, now);
    }

    public synchronized void deleteCompletedPlan(String userId) {
        delete("completed_health_plans", userId);
    }

    public synchronized void saveReminder(
            String userId,
            LocalTime time,
            LocalDate lastSentDate) {
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO health_reminders(user_id, reminder_time, last_sent_date) VALUES (?, ?, ?)
                        ON CONFLICT(user_id) DO UPDATE SET
                          reminder_time=excluded.reminder_time,
                          last_sent_date=excluded.last_sent_date
                        """)) {
            statement.setString(1, userId);
            statement.setString(2, time.toString());
            statement.setString(3, lastSentDate == null ? null : lastSentDate.toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw databaseFailure("保存健康提醒失败", exception);
        }
    }

    public synchronized Map<String, StoredReminder> loadReminders() {
        Map<String, StoredReminder> reminders = new LinkedHashMap<>();
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT user_id, reminder_time, last_sent_date FROM health_reminders");
                ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                String lastSent = result.getString("last_sent_date");
                reminders.put(result.getString("user_id"), new StoredReminder(
                        LocalTime.parse(result.getString("reminder_time")),
                        lastSent == null ? null : LocalDate.parse(lastSent)));
            }
            return Map.copyOf(reminders);
        } catch (SQLException | RuntimeException exception) {
            throw databaseFailure("读取健康提醒失败", exception);
        }
    }

    public synchronized void deleteReminder(String userId) {
        delete("health_reminders", userId);
    }

    private void initialize() {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS agent_runs(
                      user_id TEXT PRIMARY KEY,
                      payload TEXT NOT NULL,
                      expires_at INTEGER NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS completed_health_plans(
                      user_id TEXT PRIMARY KEY,
                      payload TEXT NOT NULL,
                      expires_at INTEGER NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS health_reminders(
                      user_id TEXT PRIMARY KEY,
                      reminder_time TEXT NOT NULL,
                      last_sent_date TEXT
                    )
                    """);
        } catch (SQLException exception) {
            throw databaseFailure("初始化 Agent 状态库失败", exception);
        }
    }

    private void upsert(String sql, String userId, String payload, long expiresAt) {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, userId);
            statement.setString(2, payload);
            statement.setLong(3, expiresAt);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw databaseFailure("保存 Agent 状态失败", exception);
        }
    }

    private Optional<String> loadPayload(String table, String userId, Instant now) {
        String sql = "SELECT payload, expires_at FROM " + table + " WHERE user_id = ?";
        String payload;
        boolean expired;
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                expired = result.getLong("expires_at") <= now.toEpochMilli();
                payload = result.getString("payload");
            }
        } catch (SQLException exception) {
            throw databaseFailure("读取 Agent 状态失败", exception);
        }
        if (expired) {
            delete(table, userId);
            return Optional.empty();
        }
        return Optional.of(payload);
    }

    private void delete(String table, String userId) {
        String sql = "DELETE FROM " + table + " WHERE user_id = ?";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, userId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw databaseFailure("删除 Agent 状态失败", exception);
        }
    }

    private Connection connection() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000");
        }
        return connection;
    }

    private IllegalStateException databaseFailure(String message, Exception cause) {
        return new IllegalStateException(message, cause);
    }

    public record StoredReminder(LocalTime time, LocalDate lastSentDate) {
    }
}
