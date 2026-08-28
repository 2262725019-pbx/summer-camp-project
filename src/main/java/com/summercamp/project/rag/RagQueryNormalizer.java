package com.summercamp.project.rag;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Produces deterministic local lexical features without a Chinese tokenizer.
 * Chinese runs become character bi-grams and tri-grams; ASCII words and numbers
 * remain whole terms.
 */
final class RagQueryNormalizer {

    private static final Set<String> QUESTION_STOP_TERMS = Set.of(
            "什么", "是什么", "什么意思", "么意思", "意思",
            "怎么", "怎么办", "如何", "为什么", "是否", "可以",
            "请问", "一下", "知道", "到底", "正常", "以后", "之前",
            "现在", "今天", "一个", "多少", "有没有");

    NormalizedText normalize(String value) {
        String raw = value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{P}\\p{S}]+", " ")
                .replaceAll("\\s+", " ")
                .strip();
        String compact = raw.replace(" ", "");
        return new NormalizedText(raw, compact, lexicalTerms(raw));
    }

    private Set<String> lexicalTerms(String normalizedRaw) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (String token : normalizedRaw.split(" ")) {
            if (!token.isBlank()) {
                addMixedTokenTerms(token, terms);
            }
        }
        terms.removeAll(QUESTION_STOP_TERMS);
        return java.util.Collections.unmodifiableSet(terms);
    }

    private void addMixedTokenTerms(String token, Set<String> terms) {
        StringBuilder run = new StringBuilder();
        Character.UnicodeScript currentScript = null;
        for (int offset = 0; offset < token.length();) {
            int codePoint = token.codePointAt(offset);
            Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
            Character.UnicodeScript group = script == Character.UnicodeScript.HAN
                    ? Character.UnicodeScript.HAN : Character.UnicodeScript.LATIN;
            if (currentScript != null && currentScript != group) {
                addRun(run.toString(), currentScript, terms);
                run.setLength(0);
            }
            run.appendCodePoint(codePoint);
            currentScript = group;
            offset += Character.charCount(codePoint);
        }
        if (!run.isEmpty()) {
            addRun(run.toString(), currentScript, terms);
        }
    }

    private void addRun(String run, Character.UnicodeScript script, Set<String> terms) {
        if (script != Character.UnicodeScript.HAN) {
            if (run.length() >= 2) {
                terms.add(run);
            }
            return;
        }
        int[] codePoints = run.codePoints().toArray();
        if (codePoints.length == 1) {
            return;
        }
        for (int size : List.of(2, 3)) {
            for (int start = 0; start + size <= codePoints.length; start++) {
                terms.add(new String(codePoints, start, size));
            }
        }
    }

    record NormalizedText(String raw, String compact, Set<String> terms) {
    }
}
