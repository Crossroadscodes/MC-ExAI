package com.exai.retrieval;

import com.exai.entity.GameDocument;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Immutable in-memory BM25 index tailored for mixed Chinese and ASCII game text. */
public final class Bm25Index {
    private static final double K1 = 1.2;
    private static final double B = 0.75;

    private final List<IndexedDocument> documents;
    private final Map<String, Integer> documentFrequencies;
    private final double averageDocumentLength;

    public Bm25Index(List<GameDocument> sourceDocuments) {
        List<GameDocument> safeDocuments = sourceDocuments == null
                ? Collections.<GameDocument>emptyList() : sourceDocuments;
        List<IndexedDocument> indexed = new ArrayList<>(safeDocuments.size());
        Map<String, Integer> frequencies = new HashMap<>();
        long totalLength = 0L;

        for (GameDocument document : safeDocuments) {
            List<String> tokens = tokenize(document == null ? null : document.getContent());
            Map<String, Integer> termFrequencies = new HashMap<>();
            for (String token : tokens) {
                termFrequencies.put(token, termFrequencies.getOrDefault(token, 0) + 1);
            }
            for (String term : termFrequencies.keySet()) {
                frequencies.put(term, frequencies.getOrDefault(term, 0) + 1);
            }
            indexed.add(new IndexedDocument(document, termFrequencies, tokens.size()));
            totalLength += tokens.size();
        }

        this.documents = Collections.unmodifiableList(indexed);
        this.documentFrequencies = Collections.unmodifiableMap(frequencies);
        this.averageDocumentLength = indexed.isEmpty() ? 0.0
                : (double) totalLength / indexed.size();
    }

    public List<SearchResult> search(String query, int topK) {
        if (topK <= 0 || documents.isEmpty()) return Collections.emptyList();
        Set<String> queryTerms = new LinkedHashSet<>(tokenize(query));
        if (queryTerms.isEmpty()) return Collections.emptyList();

        List<SearchResult> results = new ArrayList<>();
        for (IndexedDocument indexed : documents) {
            if (indexed.document == null) continue;
            double score = score(indexed, queryTerms);
            if (score > 0.0) results.add(new SearchResult(indexed.document, score));
        }
        results.sort(Comparator.comparingDouble(SearchResult::getScore).reversed()
                .thenComparing(result -> safeId(result.getDocument())));
        if (results.size() <= topK) return results;
        return new ArrayList<>(results.subList(0, topK));
    }

    private double score(IndexedDocument document, Set<String> queryTerms) {
        double score = 0.0;
        double normalizedLength = averageDocumentLength <= 0.0
                ? 1.0 : document.length / averageDocumentLength;
        for (String term : queryTerms) {
            Integer termFrequency = document.termFrequencies.get(term);
            Integer documentFrequency = documentFrequencies.get(term);
            if (termFrequency == null || documentFrequency == null) continue;
            double idf = Math.log(1.0 + (documents.size() - documentFrequency + 0.5)
                    / (documentFrequency + 0.5));
            double denominator = termFrequency + K1 * (1.0 - B + B * normalizedLength);
            score += idf * (termFrequency * (K1 + 1.0)) / denominator;
        }
        return score;
    }

    static List<String> tokenize(String text) {
        if (text == null || text.trim().isEmpty()) return Collections.emptyList();
        List<String> tokens = new ArrayList<>();
        StringBuilder ascii = new StringBuilder();
        StringBuilder han = new StringBuilder();

        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
                flushAscii(ascii, tokens);
                han.appendCodePoint(codePoint);
            } else {
                flushHan(han, tokens);
                if (Character.isLetterOrDigit(codePoint) || codePoint == '_') {
                    ascii.appendCodePoint(Character.toLowerCase(codePoint));
                } else {
                    flushAscii(ascii, tokens);
                }
            }
        }
        flushHan(han, tokens);
        flushAscii(ascii, tokens);
        return tokens;
    }

    private static void flushAscii(StringBuilder token, List<String> output) {
        if (token.length() == 0) return;
        output.add(token.toString().toLowerCase(Locale.ROOT));
        token.setLength(0);
    }

    private static void flushHan(StringBuilder sequence, List<String> output) {
        if (sequence.length() == 0) return;
        int[] codePoints = sequence.codePoints().toArray();
        if (codePoints.length == 1) {
            output.add(new String(codePoints, 0, 1));
        } else {
            for (int i = 0; i < codePoints.length - 1; i++) {
                output.add(new String(codePoints, i, 2));
            }
        }
        sequence.setLength(0);
    }

    private static String safeId(GameDocument document) {
        return document == null || document.getId() == null ? "" : document.getId();
    }

    private static final class IndexedDocument {
        private final GameDocument document;
        private final Map<String, Integer> termFrequencies;
        private final int length;

        private IndexedDocument(GameDocument document, Map<String, Integer> termFrequencies, int length) {
            this.document = document;
            this.termFrequencies = termFrequencies;
            this.length = length;
        }
    }

    public static final class SearchResult {
        private final GameDocument document;
        private final double score;

        private SearchResult(GameDocument document, double score) {
            this.document = document;
            this.score = score;
        }

        public GameDocument getDocument() {
            return document;
        }

        public double getScore() {
            return score;
        }
    }
}
