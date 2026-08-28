package com.summercamp.project.rag;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic field-weighted BM25-like scorer. Corpus normalization, document
 * frequency and average field lengths are computed once in the constructor.
 */
final class RagScorer {

    private static final double K1 = 1.2;
    private static final double B = 0.75;
    private static final double TITLE_WEIGHT = 3.0;
    private static final double KEYWORD_WEIGHT = 2.0;
    private static final double CONTENT_WEIGHT = 1.0;
    private static final double EXPANSION_WEIGHT = 0.10;

    private final RagQueryNormalizer normalizer;
    private final RagQueryExpansionDictionary expansionDictionary;
    private final List<IndexedDocument> indexedDocuments;
    private final Map<String, Integer> documentFrequency;
    private final double averageTitleLength;
    private final double averageKeywordLength;
    private final double averageContentLength;

    RagScorer(List<RagDocument> documents) {
        normalizer = new RagQueryNormalizer();
        expansionDictionary = new RagQueryExpansionDictionary();
        indexedDocuments = documents.stream().map(this::index).toList();
        documentFrequency = calculateDocumentFrequency(indexedDocuments);
        averageTitleLength = averageLength(indexedDocuments, Field.TITLE);
        averageKeywordLength = averageLength(indexedDocuments, Field.KEYWORD);
        averageContentLength = averageLength(indexedDocuments, Field.CONTENT);
    }

    List<ScoredDocument> score(String query) {
        RagQueryNormalizer.NormalizedText original = normalizer.normalize(query);
        LinkedHashSet<String> expandedTerms = new LinkedHashSet<>(original.terms());
        List<String> expandedPhrases = expansionDictionary.expand(original.raw());
        for (String phrase : expandedPhrases) {
            expandedTerms.addAll(normalizer.normalize(phrase).terms());
        }
        LinkedHashSet<String> expansionOnlyTerms = new LinkedHashSet<>(expandedTerms);
        expansionOnlyTerms.removeAll(original.terms());
        QueryFeatures features = new QueryFeatures(
                original, java.util.Collections.unmodifiableSet(expansionOnlyTerms), expandedPhrases);
        return indexedDocuments.stream().map(document -> score(document, features)).toList();
    }

    private ScoredDocument score(IndexedDocument indexed, QueryFeatures query) {
        double titleLexical = bm25(query.original().terms(), indexed.titleTerms(), averageTitleLength)
                + EXPANSION_WEIGHT * bm25(
                        query.expansionTerms(), indexed.titleTerms(), averageTitleLength);
        double keywordLexical = bm25(query.original().terms(), indexed.keywordTerms(), averageKeywordLength)
                + EXPANSION_WEIGHT * bm25(
                        query.expansionTerms(), indexed.keywordTerms(), averageKeywordLength);
        double contentLexical = bm25(query.original().terms(), indexed.contentTerms(), averageContentLength)
                + EXPANSION_WEIGHT * bm25(
                        query.expansionTerms(), indexed.contentTerms(), averageContentLength);

        int titleScore = rounded(titleLexical * TITLE_WEIGHT)
                + exactTitleScore(query, indexed.normalizedTitle());
        int keywordScore = rounded(keywordLexical * KEYWORD_WEIGHT)
                + exactKeywordScore(query, indexed.normalizedKeywords());
        int contentScore = rounded(contentLexical * CONTENT_WEIGHT)
                + exactContentScore(query, indexed.normalizedContent());

        Set<String> allDocumentTerms = new HashSet<>(indexed.titleTerms().keySet());
        allDocumentTerms.addAll(indexed.keywordTerms().keySet());
        allDocumentTerms.addAll(indexed.contentTerms().keySet());
        long covered = query.original().terms().stream().filter(allDocumentTerms::contains).count();
        double coverage = query.original().terms().isEmpty()
                ? 0.0 : (double) covered / query.original().terms().size();
        int coverageScore = rounded(coverage * 12.0);
        RagScoreBreakdown breakdown = new RagScoreBreakdown(
                titleScore, keywordScore, contentScore, coverageScore,
                titleScore + keywordScore + contentScore + coverageScore);
        return new ScoredDocument(indexed.document(), breakdown, (int) covered, coverage);
    }

    private int exactTitleScore(QueryFeatures query, String title) {
        int best = 0;
        for (String phrase : query.expandedPhrases()) {
            String compactPhrase = normalizer.normalize(phrase).compact();
            if (compactPhrase.isBlank()) {
                continue;
            }
            if (compactPhrase.equals(title)) {
                best = Math.max(best, 30);
            } else if (compactPhrase.length() >= 2
                    && (compactPhrase.contains(title) || title.contains(compactPhrase))) {
                best = Math.max(best, 18);
            }
        }
        return best;
    }

    private int exactKeywordScore(QueryFeatures query, List<String> keywords) {
        int strongestSignal = 0;
        for (String keyword : keywords) {
            int best = 0;
            for (String phrase : query.expandedPhrases()) {
                String compactPhrase = normalizer.normalize(phrase).compact();
                if (compactPhrase.equals(keyword)) {
                    best = Math.max(best, 20);
                } else if (keyword.length() >= 2 && compactPhrase.contains(keyword)) {
                    best = Math.max(best, 14);
                } else if (compactPhrase.length() >= 2 && keyword.contains(compactPhrase)) {
                    best = Math.max(best, 8);
                }
            }
            strongestSignal = Math.max(strongestSignal, best);
        }
        return strongestSignal;
    }

    private int exactContentScore(QueryFeatures query, String content) {
        String compactQuery = query.original().compact();
        return compactQuery.length() >= 4 && content.contains(compactQuery) ? 8 : 0;
    }

    private double bm25(Set<String> queryTerms, Map<String, Integer> fieldTerms, double averageLength) {
        if (queryTerms.isEmpty() || fieldTerms.isEmpty()) {
            return 0.0;
        }
        int fieldLength = fieldTerms.values().stream().mapToInt(Integer::intValue).sum();
        double score = 0.0;
        for (String term : queryTerms) {
            int tf = fieldTerms.getOrDefault(term, 0);
            if (tf == 0) {
                continue;
            }
            int df = documentFrequency.getOrDefault(term, 0);
            double idf = Math.log(1.0 + (indexedDocuments.size() - df + 0.5) / (df + 0.5));
            double lengthRatio = averageLength == 0.0 ? 1.0 : fieldLength / averageLength;
            double denominator = tf + K1 * (1.0 - B + B * lengthRatio);
            score += idf * (tf * (K1 + 1.0)) / denominator;
        }
        return score;
    }

    private IndexedDocument index(RagDocument document) {
        RagQueryNormalizer.NormalizedText title = normalizer.normalize(document.title());
        List<RagQueryNormalizer.NormalizedText> keywords = document.keywords().stream()
                .map(normalizer::normalize).toList();
        RagQueryNormalizer.NormalizedText content = normalizer.normalize(document.content());
        return new IndexedDocument(
                document,
                title.compact(),
                keywords.stream().map(RagQueryNormalizer.NormalizedText::compact).toList(),
                content.compact(),
                frequencies(title.terms()),
                frequencies(keywords.stream().flatMap(keyword -> keyword.terms().stream()).toList()),
                frequencies(content.terms()));
    }

    private Map<String, Integer> calculateDocumentFrequency(List<IndexedDocument> documents) {
        Map<String, Integer> frequencies = new HashMap<>();
        for (IndexedDocument document : documents) {
            Set<String> unique = new HashSet<>(document.titleTerms().keySet());
            unique.addAll(document.keywordTerms().keySet());
            unique.addAll(document.contentTerms().keySet());
            unique.forEach(term -> frequencies.merge(term, 1, Integer::sum));
        }
        return Map.copyOf(frequencies);
    }

    private double averageLength(List<IndexedDocument> documents, Field field) {
        return documents.stream().mapToInt(document -> switch (field) {
            case TITLE -> document.titleTerms().values().stream().mapToInt(Integer::intValue).sum();
            case KEYWORD -> document.keywordTerms().values().stream().mapToInt(Integer::intValue).sum();
            case CONTENT -> document.contentTerms().values().stream().mapToInt(Integer::intValue).sum();
        }).average().orElse(0.0);
    }

    private Map<String, Integer> frequencies(Iterable<String> terms) {
        Map<String, Integer> frequencies = new HashMap<>();
        terms.forEach(term -> frequencies.merge(term, 1, Integer::sum));
        return Map.copyOf(frequencies);
    }

    private int rounded(double score) {
        return (int) Math.round(score);
    }

    record ScoredDocument(
            RagDocument document,
            RagScoreBreakdown breakdown,
            int matchedOriginalTerms,
            double coverage) {
    }

    private record QueryFeatures(
            RagQueryNormalizer.NormalizedText original,
            Set<String> expansionTerms,
            List<String> expandedPhrases) {
    }

    private record IndexedDocument(
            RagDocument document,
            String normalizedTitle,
            List<String> normalizedKeywords,
            String normalizedContent,
            Map<String, Integer> titleTerms,
            Map<String, Integer> keywordTerms,
            Map<String, Integer> contentTerms) {
    }

    private enum Field {
        TITLE,
        KEYWORD,
        CONTENT
    }
}
