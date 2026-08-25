package com.summercamp.project.agent.store;

import com.summercamp.project.agent.model.AgentRun;
import java.util.Optional;

public interface AgentRunStore {

    void save(AgentRun run);

    Optional<AgentRun> latest(String userId);

    void clear(String userId);
}
