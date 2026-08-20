package com.summercamp.project.conversation;

import com.summercamp.project.llm.ChatMessage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Per-user, in-memory conversation history. Image bytes are deliberately never stored here. */
@Component
public class InMemoryConversationMemoryStore implements ConversationMemoryStore {

    static final int MAX_MESSAGES = 20;
    static final int MAX_CHARACTERS = 12_000;
    static final Duration TIME_TO_LIVE = Duration.ofMinutes(30);

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryConversationMemoryStore() {
        this(Clock.systemUTC());
    }

    InMemoryConversationMemoryStore(Clock clock) {
        this.clock = clock;
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
                return List.of();
            }
            return List.copyOf(session.messages);
        }
    }

    @Override
    public void recordExchange(String userId, String userText, String assistantText) {
        Session session = sessions.computeIfAbsent(userId, ignored -> new Session());
        synchronized (session) {
            if (isExpired(session)) {
                session.messages.clear();
                session.characters = 0;
            }
            add(session, ChatMessage.user(userText));
            add(session, ChatMessage.assistant(assistantText));
            session.updatedAt = clock.instant();
            trim(session);
        }
    }

    @Override
    public void clear(String userId) {
        sessions.remove(userId);
    }

    private void add(Session session, ChatMessage message) {
        session.messages.addLast(message);
        session.characters += message.content().length();
    }

    private void trim(Session session) {
        while (session.messages.size() > MAX_MESSAGES
                || session.characters > MAX_CHARACTERS) {
            removeFirst(session);
            if (!session.messages.isEmpty()
                    && "assistant".equals(session.messages.getFirst().role())) {
                removeFirst(session);
            }
        }
    }

    private void removeFirst(Session session) {
        ChatMessage removed = session.messages.removeFirst();
        session.characters -= removed.content().length();
    }

    private boolean isExpired(Session session) {
        return session.updatedAt.plus(TIME_TO_LIVE).isBefore(clock.instant());
    }

    private final class Session {
        private final Deque<ChatMessage> messages = new ArrayDeque<>();
        private int characters;
        private Instant updatedAt = clock.instant();
    }
}
