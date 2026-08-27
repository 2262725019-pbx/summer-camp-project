package com.summercamp.project.llm;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 简单的内存断点存储，用于多轮工具调用失败后恢复。
 * 生产环境可替换为 Redis 或数据库。
 */
@Component
public class ToolExecutionStateStore {

    private final Map<String, ExecutionState> states = new ConcurrentHashMap<>();

    public void save(String sessionId, ExecutionState state) {
        states.put(sessionId, state);
    }

    public ExecutionState get(String sessionId) {
        return states.get(sessionId);
    }

    public void remove(String sessionId) {
        states.remove(sessionId);
    }

    public record ExecutionState(
        JsonNode payload,
        List<ChatOutcome.Media> media,
        int currentRound,
        String model
    ) {}
}
