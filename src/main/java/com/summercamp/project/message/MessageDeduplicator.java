package com.summercamp.project.message;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class MessageDeduplicator {

    private static final int MAX_REMEMBERED_IDS = 1_000;
    private final Map<String, Boolean> seen = new LinkedHashMap<>();

    public synchronized boolean firstSeen(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return true;
        }
        if (seen.putIfAbsent(messageId, Boolean.TRUE) != null) {
            return false;
        }
        if (seen.size() > MAX_REMEMBERED_IDS) {
            String oldest = seen.keySet().iterator().next();
            seen.remove(oldest);
        }
        return true;
    }
}
