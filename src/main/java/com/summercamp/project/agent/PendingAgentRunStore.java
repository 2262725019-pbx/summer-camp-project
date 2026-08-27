package com.summercamp.project.agent;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public final class PendingAgentRunStore {

    public static final Duration TTL = Duration.ofMinutes(15);

    private final ConcurrentHashMap<String, AgentRunCheckpoint> entries =
            new ConcurrentHashMap<>();
    private final Clock clock;

    public PendingAgentRunStore() {
        this(Clock.systemUTC());
    }

    PendingAgentRunStore(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public Optional<AgentRunCheckpoint> rememberInitial(
            String userId,
            AgentRunRequest request,
            AgentRunResult result
    ) {
        Instant now = clock.instant();
        Optional<AgentRunCheckpoint> checkpoint = AgentRunCheckpoint.capture(
                request.goal(), result, 0, now, now.plus(TTL), request.voiceMessage());
        checkpoint.ifPresent(value -> remember(userId, value));
        return checkpoint;
    }

    public Optional<AgentRunCheckpoint> rememberResumed(
            String userId,
            AgentRunCheckpoint previous,
            AgentRunResult result
    ) {
        Objects.requireNonNull(previous, "previous checkpoint must not be null");
        if (result == null || result.plan() == null || !previous.plan().equals(result.plan())) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        Optional<AgentRunCheckpoint> checkpoint = AgentRunCheckpoint.capture(
                previous.originalGoal(),
                result,
                previous.resumeAttemptCount() + 1,
                previous.createdAt(),
                now.plus(TTL),
                previous.originalVoiceMessage());
        checkpoint.ifPresent(value -> remember(userId, value));
        return checkpoint;
    }

    public void remember(String userId, AgentRunCheckpoint checkpoint) {
        String key = requireUserId(userId);
        AgentRunCheckpoint value = Objects.requireNonNull(checkpoint, "checkpoint must not be null")
                .refreshExpiry(clock.instant().plus(TTL));
        entries.put(key, value);
    }

    public Optional<AgentRunCheckpoint> get(String userId) {
        String key = requireUserId(userId);
        AgentRunCheckpoint checkpoint = entries.get(key);
        if (checkpoint == null) {
            return Optional.empty();
        }
        if (!checkpoint.expiresAt().isAfter(clock.instant())) {
            entries.remove(key, checkpoint);
            return Optional.empty();
        }
        return Optional.of(checkpoint);
    }

    public void clear(String userId) {
        entries.remove(requireUserId(userId));
    }

    private String requireUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        return userId;
    }
}
