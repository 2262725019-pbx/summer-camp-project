package com.summercamp.project.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/** 按微信用户隔离的内存待办服务；程序重启后自动清空。 */
@Service
public class TodoService {

    static final int MAX_ITEMS_PER_USER = 100;
    static final int MAX_ITEM_CHARACTERS = 500;

    private final Map<String, List<String>> todos = new ConcurrentHashMap<>();

    public int add(String userId, String item) {
        String key = requireUserId(userId);
        String normalized = item == null ? "" : item.strip();
        if (normalized.isBlank()) {
            throw new ToolExecutionException("待办内容不能为空");
        }
        if (normalized.length() > MAX_ITEM_CHARACTERS) {
            throw new ToolExecutionException("待办内容不能超过 " + MAX_ITEM_CHARACTERS + " 个字符");
        }
        List<String> items = todos.computeIfAbsent(key, ignored -> new ArrayList<>());
        synchronized (items) {
            if (items.size() >= MAX_ITEMS_PER_USER) {
                throw new ToolExecutionException("待办数量已达到上限 " + MAX_ITEMS_PER_USER);
            }
            items.add(normalized);
            return items.size();
        }
    }

    public List<String> list(String userId) {
        List<String> items = todos.get(requireUserId(userId));
        if (items == null) {
            return List.of();
        }
        synchronized (items) {
            return List.copyOf(items);
        }
    }

    public String complete(String userId, int index) {
        List<String> items = todos.get(requireUserId(userId));
        if (items == null) {
            return null;
        }
        synchronized (items) {
            if (index < 1 || index > items.size()) {
                return null;
            }
            String completed = items.remove(index - 1);
            if (items.isEmpty()) {
                todos.remove(userId, items);
            }
            return completed;
        }
    }

    private String requireUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new ToolExecutionException("当前工具需要有效的用户会话");
        }
        return userId;
    }
}
