package com.summercamp.project.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.summercamp.project.llm.ChatMessage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Per-user, in-memory conversation history. Image bytes are deliberately never stored here.
 * 变更即写 JSON 文件，重启自动加载恢复；30 分钟无交互的会话在下次访问时过期清除。
 */
@Component
public class InMemoryConversationMemoryStore implements ConversationMemoryStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(InMemoryConversationMemoryStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 保留最近的会话消息条数；更早的消息折叠进压缩摘要，避免 prompt 无限膨胀。 */
    static final int MAX_MESSAGES = 10;
    static final int MAX_CHARACTERS = 12_000;
    /** 压缩摘要的最大字符数。 */
    static final int SUMMARY_MAX_CHARS = 1_500;
    /** 摘要中单条消息的最大字符数。 */
    static final int CONDENSED_MESSAGE_CHARS = 100;
    static final Duration TIME_TO_LIVE = Duration.ofMinutes(30);
    /** 同时保留的用户会话数上限，超出时淘汰最久未交互的会话，防止长跑内存膨胀。 */
    static final int MAX_SESSIONS = 500;

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Path filePath;

    public InMemoryConversationMemoryStore() {
        this(Clock.systemUTC(), null);
    }

    InMemoryConversationMemoryStore(Clock clock) {
        this(clock, null);
    }

    @Autowired
    public InMemoryConversationMemoryStore(
            @Value("${conversation.history-file:runtime/conversations.json}") String filePath) {
        this(Clock.systemUTC(), filePath);
    }

    InMemoryConversationMemoryStore(Clock clock, String filePath) {
        this.clock = clock;
        this.filePath = filePath == null || filePath.isBlank() ? null : Path.of(filePath);
        load();
    }

    @Override
    public List<ChatMessage> history(String userId) {
        Session session = sessions.get(userId);
        if (session == null) {
            return List.of();
        }
        synchronized (session) {
            if (isExpired(session)) {
                sessions.remove(userId, session);
                save();
                return List.of();
            }
            List<ChatMessage> result = new ArrayList<>(session.messages.size() + 1);
            if (!session.earlierSummary.isBlank()) {
                result.add(ChatMessage.system("较早的对话（已压缩）：\n" + session.earlierSummary));
            }
            result.addAll(session.messages);
            return List.copyOf(result);
        }
    }

    @Override
    public void recordExchange(String userId, String userText, String assistantText) {
        Session session = sessions.computeIfAbsent(userId, ignored -> new Session());
        synchronized (session) {
            if (isExpired(session)) {
                session.messages.clear();
                session.earlierSummary = "";
                session.characters = 0;
            }
            add(session, ChatMessage.user(userText));
            add(session, ChatMessage.assistant(assistantText));
            session.updatedAt = clock.instant();
            trim(session);
        }
        evictIfOverCapacity();
        save();
    }

    /** 会话数超过上限时，淘汰最久未交互的会话。 */
    private void evictIfOverCapacity() {
        if (sessions.size() <= MAX_SESSIONS) {
            return;
        }
        String oldestUserId = null;
        Instant oldest = Instant.MAX;
        for (Map.Entry<String, Session> entry : sessions.entrySet()) {
            if (entry.getValue().updatedAt.isBefore(oldest)) {
                oldest = entry.getValue().updatedAt;
                oldestUserId = entry.getKey();
            }
        }
        if (oldestUserId != null) {
            sessions.remove(oldestUserId);
        }
    }

    @Override
    public void clear(String userId) {
        if (sessions.remove(userId) != null) {
            save();
        }
    }

    private void add(Session session, ChatMessage message) {
        session.messages.addLast(message);
        session.characters += message.content().length();
    }

    private void trim(Session session) {
        while ((session.messages.size() > MAX_MESSAGES
                || session.characters > MAX_CHARACTERS)
                && session.messages.size() >= 2) {
            if (!foldOldestPair(session)) {
                break;
            }
        }
    }

    /**
     * 把最早的一对（用户+助手）消息折叠进压缩摘要，替代原先的直接丢弃，
     * 使被裁剪的上下文仍以极低成本保留在 prompt 中。
     *
     * @return 摘要还能容纳时返回 true；摘要已满返回 false 表示后续对按丢弃处理
     */
    private boolean foldOldestPair(Session session) {
        ChatMessage userMessage = session.messages.removeFirst();
        ChatMessage assistantMessage = session.messages.removeFirst();
        session.characters -= userMessage.content().length() + assistantMessage.content().length();
        String condensed = "用户：" + condense(userMessage.content())
                + "\n助手：" + condense(assistantMessage.content());
        if (session.earlierSummary.length() + condensed.length() > SUMMARY_MAX_CHARS) {
            return false;
        }
        session.earlierSummary = session.earlierSummary.isBlank()
                ? condensed
                : session.earlierSummary + "\n" + condensed;
        return true;
    }

    private String condense(String content) {
        if (content == null) {
            return "";
        }
        String normalized = content.replace('\n', ' ').strip();
        return normalized.length() <= CONDENSED_MESSAGE_CHARS
                ? normalized
                : normalized.substring(0, CONDENSED_MESSAGE_CHARS) + "…";
    }

    private boolean isExpired(Session session) {
        return session.updatedAt.plus(TIME_TO_LIVE).isBefore(clock.instant());
    }

    // ---------------------------------------------------------------- 持久化

    private void load() {
        if (filePath == null || !Files.exists(filePath)) {
            return;
        }
        try {
            JsonNode root = MAPPER.readTree(Files.readAllBytes(filePath));
            if (!root.isArray()) {
                LOGGER.warn("会话历史文件格式异常，忽略：{}", filePath);
                return;
            }
            for (JsonNode node : root) {
                String userId = node.path("userId").asText();
                if (userId.isBlank()) {
                    continue;
                }
                Session session = new Session();
                session.earlierSummary = node.path("earlierSummary").asText("");
                session.updatedAt = Instant.parse(node.path("updatedAt").asText());
                for (JsonNode message : node.path("messages")) {
                    session.messages.addLast(new ChatMessage(
                            message.path("role").asText(),
                            message.path("content").asText()));
                    session.characters += message.path("content").asText().length();
                }
                if (!isExpired(session)) {
                    sessions.put(userId, session);
                }
            }
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("读取会话历史文件失败：{}", exception.getMessage());
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
            for (Map.Entry<String, Session> entry : sessions.entrySet()) {
                Session session = entry.getValue();
                ObjectNode node = array.addObject();
                node.put("userId", entry.getKey());
                node.put("earlierSummary", session.earlierSummary);
                node.put("updatedAt", session.updatedAt.toString());
                ArrayNode messages = node.putArray("messages");
                for (ChatMessage message : session.messages) {
                    ObjectNode messageNode = messages.addObject();
                    messageNode.put("role", message.role());
                    messageNode.put("content", message.content());
                }
            }
            Files.writeString(filePath, MAPPER.writeValueAsString(array));
        } catch (IOException exception) {
            LOGGER.warn("写入会话历史文件失败：{}", exception.getMessage());
        }
    }

    private final class Session {
        private final Deque<ChatMessage> messages = new ArrayDeque<>();
        private String earlierSummary = "";
        private int characters;
        private Instant updatedAt = clock.instant();
    }
}
