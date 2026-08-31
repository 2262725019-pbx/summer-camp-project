package com.summercamp.project.agent.store;

import com.summercamp.project.agent.model.AgentRun;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "agent.persistence.enabled", havingValue = "false")
public class InMemoryAgentRunStore implements AgentRunStore {

    private final ConcurrentHashMap<String, AgentRun> runs = new ConcurrentHashMap<>();
    private final Clock clock = Clock.systemUTC();

    @Override
    public void save(AgentRun run) {
        runs.put(run.userId(), run);
    }

    @Override
    public Optional<AgentRun> latest(String userId) {
        AgentRun run = runs.get(userId);
        if (run == null) {
            return Optional.empty();
        }
        if (!run.expiresAt().isAfter(clock.instant())) {
            runs.remove(userId, run);
            return Optional.empty();
        }
        return Optional.of(run);
    }

    @Override
    public void clear(String userId) {
        runs.remove(userId);
    }
}
