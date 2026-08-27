package com.summercamp.project.tool;

import java.util.Objects;
import java.util.Set;

/** Immutable request-scoped capability boundary for model-requested tools. */
public record ToolAccessPolicy(Mode mode, Set<String> toolNames) {
    private static final ToolAccessPolicy UNRESTRICTED =
            new ToolAccessPolicy(Mode.UNRESTRICTED, Set.of());

    public ToolAccessPolicy {
        mode = Objects.requireNonNull(mode, "mode must not be null");
        toolNames = toolNames == null ? Set.of() : Set.copyOf(toolNames);
        if (toolNames.stream().anyMatch(name -> name == null || name.isBlank())) {
            throw new IllegalArgumentException("tool names must not be blank");
        }
        if (mode == Mode.UNRESTRICTED && !toolNames.isEmpty()) {
            throw new IllegalArgumentException("unrestricted policy must not list tools");
        }
    }

    public static ToolAccessPolicy unrestricted() {
        return UNRESTRICTED;
    }

    public static ToolAccessPolicy allowOnly(Set<String> allowedTools) {
        return new ToolAccessPolicy(Mode.ALLOW_ONLY, allowedTools);
    }

    public static ToolAccessPolicy allExcept(Set<String> excludedTools) {
        return new ToolAccessPolicy(Mode.ALL_EXCEPT, excludedTools);
    }

    public boolean allows(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        return switch (mode) {
            case UNRESTRICTED -> true;
            case ALLOW_ONLY -> toolNames.contains(toolName);
            case ALL_EXCEPT -> !toolNames.contains(toolName);
        };
    }

    public enum Mode {
        UNRESTRICTED,
        ALLOW_ONLY,
        ALL_EXCEPT
    }
}
