package com.summercamp.project.conversation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Deterministic relevance-first scorer with a small bounded recency tie-breaker. */
final class ConversationMemoryScorer {

    private static final int MINIMUM_CONFIDENCE = 8;
    private static final Set<String> QUESTION_STOP_TERMS = Set.of(
            "什么", "是什么", "什么意思", "意思", "怎么", "如何", "为什么",
            "是否", "可以", "请问", "一下", "知道", "刚才", "之前", "以前",
            "现在", "今天", "还是", "说过");
    private static final List<List<String>> EXPANSION_GROUPS = List.of(
            List.of("喜欢", "偏好", "爱用"),
            List.of("顺序", "次序", "先展示", "再演示"),
            List.of("地点", "位置", "地方"),
            List.of("答辩", "汇报", "展示"),
            List.of("重点", "核心", "要点"),
            List.of("后端", "服务端"),
            List.of("语言", "java"),
            List.of("保存", "保留", "记住"));

    IndexedEntry index(ConversationMemoryEntry entry) {
        return new IndexedEntry(
                entry,
                normalize(entry.userText()),
                normalize(entry.assistantText()));
    }

    List<ScoredEntry> score(String query, List<IndexedEntry> candidates) {
        NormalizedText original = normalize(query);
        Set<String> expansionTerms = expansionTerms(original.raw(), original.terms());
        Map<String, Integer> documentFrequency = documentFrequency(candidates);
        List<ScoredEntry> scored = new ArrayList<>();
        for (int index = 0; index < candidates.size(); index++) {
            IndexedEntry candidate = candidates.get(index);
            scored.add(score(
                    original,
                    expansionTerms,
                    candidate,
                    candidates.size(),
                    index,
                    documentFrequency));
        }
        return scored.stream()
                .filter(ScoredEntry::confident)
                .sorted(java.util.Comparator.comparingInt(ScoredEntry::score).reversed()
                        .thenComparing(
                                scoredEntry -> scoredEntry.entry().entry().createdAt(),
                                java.util.Comparator.reverseOrder())
                        .thenComparing(scoredEntry -> scoredEntry.entry().entry().entryId()))
                .toList();
    }

    private ScoredEntry score(
            NormalizedText query,
            Set<String> expansionTerms,
            IndexedEntry candidate,
            int candidateCount,
            int candidateIndex,
            Map<String, Integer> documentFrequency) {
        Set<String> originalMatches = matches(query.terms(), candidate);
        Set<String> expansionMatches = matches(expansionTerms, candidate);
        int exactScore = exactScore(query.compact(), candidate);
        double lexical = weightedLexical(originalMatches, candidate, documentFrequency, candidateCount)
                + 0.5 * weightedLexical(
                        expansionMatches, candidate, documentFrequency, candidateCount);
        double coverage = query.terms().isEmpty()
                ? 0.0 : (double) originalMatches.size() / query.terms().size();
        int coverageScore = (int) Math.round(coverage * 12.0);
        int expansionScore = expansionMatches.isEmpty() ? 0 : 6;
        int recencyScore = candidateCount <= 1
                ? 0 : (int) Math.round(2.0 * candidateIndex / (candidateCount - 1));
        int total = exactScore + (int) Math.round(lexical) + coverageScore
                + expansionScore + recencyScore;
        boolean evidence = exactScore > 0
                || originalMatches.size() >= 2
                || (!originalMatches.isEmpty() && !expansionMatches.isEmpty());
        return new ScoredEntry(candidate, total, evidence && total >= MINIMUM_CONFIDENCE);
    }

    private int exactScore(String query, IndexedEntry candidate) {
        if (query.length() < 4) {
            return 0;
        }
        if (candidate.user().compact().contains(query) || query.contains(candidate.user().compact())) {
            return 24;
        }
        if (candidate.assistant().compact().contains(query)
                || query.contains(candidate.assistant().compact())) {
            return 10;
        }
        return 0;
    }

    private double weightedLexical(
            Set<String> matches,
            IndexedEntry candidate,
            Map<String, Integer> documentFrequency,
            int candidateCount) {
        double score = 0.0;
        for (String term : matches) {
            int df = documentFrequency.getOrDefault(term, 0);
            double idf = Math.log(1.0 + (candidateCount - df + 0.5) / (df + 0.5));
            score += idf * (candidate.user().terms().contains(term) ? 4.0 : 2.0);
        }
        return score;
    }

    private Set<String> matches(Set<String> terms, IndexedEntry candidate) {
        LinkedHashSet<String> matches = new LinkedHashSet<>();
        for (String term : terms) {
            if (candidate.user().terms().contains(term)
                    || candidate.assistant().terms().contains(term)) {
                matches.add(term);
            }
        }
        return matches;
    }

    private Map<String, Integer> documentFrequency(List<IndexedEntry> candidates) {
        Map<String, Integer> frequency = new HashMap<>();
        for (IndexedEntry candidate : candidates) {
            Set<String> terms = new HashSet<>(candidate.user().terms());
            terms.addAll(candidate.assistant().terms());
            terms.forEach(term -> frequency.merge(term, 1, Integer::sum));
        }
        return frequency;
    }

    private Set<String> expansionTerms(String query, Set<String> originalTerms) {
        LinkedHashSet<String> expansions = new LinkedHashSet<>();
        String compact = query.replace(" ", "");
        for (List<String> group : EXPANSION_GROUPS) {
            boolean triggered = group.stream()
                    .anyMatch(term -> query.contains(term) || compact.contains(term.replace(" ", "")));
            if (triggered) {
                group.forEach(term -> expansions.addAll(normalize(term).terms()));
            }
        }
        expansions.removeAll(originalTerms);
        return Collections.unmodifiableSet(expansions);
    }

    private NormalizedText normalize(String text) {
        String raw = text == null ? "" : text.toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{P}\\p{S}]+", " ")
                .replaceAll("\\s+", " ")
                .strip();
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (String token : raw.split(" ")) {
            addTokenTerms(token, terms);
        }
        terms.removeAll(QUESTION_STOP_TERMS);
        return new NormalizedText(
                raw,
                raw.replace(" ", ""),
                Collections.unmodifiableSet(terms));
    }

    private void addTokenTerms(String token, Set<String> terms) {
        if (token.isBlank()) {
            return;
        }
        StringBuilder run = new StringBuilder();
        boolean han = false;
        boolean initialized = false;
        for (int offset = 0; offset < token.length();) {
            int codePoint = token.codePointAt(offset);
            boolean currentHan = Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
            if (initialized && currentHan != han) {
                addRun(run.toString(), han, terms);
                run.setLength(0);
            }
            run.appendCodePoint(codePoint);
            han = currentHan;
            initialized = true;
            offset += Character.charCount(codePoint);
        }
        addRun(run.toString(), han, terms);
    }

    private void addRun(String run, boolean han, Set<String> terms) {
        if (!han) {
            if (run.length() >= 2) {
                terms.add(run);
            }
            return;
        }
        int[] codePoints = run.codePoints().toArray();
        for (int size : List.of(2, 3)) {
            for (int start = 0; start + size <= codePoints.length; start++) {
                terms.add(new String(codePoints, start, size));
            }
        }
    }

    record IndexedEntry(
            ConversationMemoryEntry entry,
            NormalizedText user,
            NormalizedText assistant) {
    }

    record ScoredEntry(IndexedEntry entry, int score, boolean confident) {
    }

    record NormalizedText(String raw, String compact, Set<String> terms) {
    }
}
