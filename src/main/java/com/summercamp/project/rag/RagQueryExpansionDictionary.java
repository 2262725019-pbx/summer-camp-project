package com.summercamp.project.rag;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Small, immutable and auditable synonym groups used for local query expansion. */
final class RagQueryExpansionDictionary {

    private static final List<List<String>> GROUPS = List.of(
            List.of("key", "密钥", "api key", "apikey"),
            List.of("扫码", "二维码"),
            List.of("上下文", "记忆", "历史", "聊天记录", "对话记录"),
            List.of("函数调用", "function calling"),
            List.of("工具", "tool"),
            List.of("技能", "skill"),
            List.of("检索增强", "rag"),
            List.of("框架", "技术栈", "技术架构"),
            List.of("网络", "局域网"),
            List.of("图片", "图像"));

    private final Map<String, List<String>> expansions;

    RagQueryExpansionDictionary() {
        java.util.LinkedHashMap<String, List<String>> index = new java.util.LinkedHashMap<>();
        for (List<String> group : GROUPS) {
            List<String> immutableGroup = List.copyOf(group);
            for (String term : group) {
                index.put(term, immutableGroup);
            }
        }
        expansions = java.util.Collections.unmodifiableMap(index);
    }

    List<String> expand(String normalizedQuery) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        result.add(normalizedQuery);
        String compactQuery = normalizedQuery.replace(" ", "");
        for (Map.Entry<String, List<String>> entry : expansions.entrySet()) {
            String trigger = entry.getKey();
            if (normalizedQuery.contains(trigger) || compactQuery.contains(trigger.replace(" ", ""))) {
                result.addAll(entry.getValue());
            }
        }
        return List.copyOf(result);
    }
}
