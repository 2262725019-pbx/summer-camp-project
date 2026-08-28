package com.summercamp.project.conversation;

import java.util.regex.Pattern;

/** Redacts credential-shaped text before an exchange can enter session memory. */
final class MemoryTextSanitizer {

    private static final Pattern AUTHORIZATION = Pattern.compile(
            "(?i)(authorization\\s*[:=：]\\s*)(?:bearer\\s+)?[^\\s,;，；]+");
    private static final Pattern NAMED_SECRET = Pattern.compile(
            "(?i)((?:api[-_ ]?key|access[-_ ]?token|password|secret|token|密码|密钥)"
                    + "\\s*(?:[:=：]|是)\\s*)"
                    + "[^\\s,;，；]+");
    private static final Pattern COMMON_SECRET_PREFIX = Pattern.compile(
            "(?i)\\b(?:sk|ak|key)-[a-z0-9_-]{8,}\\b");

    String sanitize(String text) {
        String safe = text == null ? "" : text;
        safe = AUTHORIZATION.matcher(safe).replaceAll("$1[REDACTED]");
        safe = NAMED_SECRET.matcher(safe).replaceAll("$1[REDACTED]");
        return COMMON_SECRET_PREFIX.matcher(safe).replaceAll("[REDACTED]");
    }
}
