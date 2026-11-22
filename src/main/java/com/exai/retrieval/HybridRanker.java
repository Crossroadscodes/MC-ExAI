package com.exai.retrieval;

import com.exai.entity.GameDocument;
import com.exai.entity.RetrievedDocument;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Combines independently ranked semantic and lexical candidates using weighted RRF. */
public final class HybridRanker {
    private HybridRanker() {
    }

    public static List<RetrievedDocument> fuse(List<ScoredDocument> embeddingResults,
                                               List<ScoredDocument> bm25Results,
                                               double embeddingWeight,
                                               double bm25Weight,
                                               int rrfK,
                                               int maxDocs) {
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        collect(candidates, embeddingResults, true);
        collect(candidates, bm25Results, false);

        List<Candidate> ranked = new ArrayList<>(candidates.values());
        for (Candidate candidate : ranked) {
            candidate.fusedScore = contribution(embeddingWeight, rrfK, candidate.embeddingRank)
                    + contribution(bm25Weight, rrfK, candidate.bm25Rank);
        }
        ranked.sort(Comparator.comparingDouble((Candidate candidate) -> candidate.fusedScore).reversed()
                .thenComparing(Comparator.comparingDouble(
                        (Candidate candidate) -> candidate.embeddingScore).reversed())
                .thenComparing(candidate -> safeId(candidate.document)));

        int limit = Math.min(Math.max(0, maxDocs), ranked.size());
        List<RetrievedDocument> output = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            Candidate candidate = ranked.get(i);
            output.add(new RetrievedDocument(candidate.document, candidate.embeddingScore,
                    candidate.bm25Score, candidate.fusedScore, i + 1,
                    candidate.embeddingRank, candidate.bm25Rank));
        }
        return output;
    }

    private static void collect(Map<String, Candidate> candidates, List<ScoredDocument> results,
                                boolean embedding) {
        if (results == null) return;
        for (int i = 0; i < results.size(); i++) {
            ScoredDocument result = results.get(i);
            if (result == null || result.document == null) continue;
            String key = safeId(result.document);
            Candidate candidate = candidates.get(key);
            if (candidate == null) {
                candidate = new Candidate(result.document);
                candidates.put(key, candidate);
            }
            if (embedding) {
                candidate.embeddingRank = i + 1;
                candidate.embeddingScore = result.score;
            } else {
                candidate.bm25Rank = i + 1;
                candidate.bm25Score = result.score;
            }
        }
    }

    private static double contribution(double weight, int rrfK, int rank) {
        return rank <= 0 ? 0.0 : weight / (Math.max(1, rrfK) + rank);
    }

    private static String safeId(GameDocument document) {
        if (document.getId() != null) return document.getId();
        return Integer.toHexString(System.identityHashCode(document));
    }

    public static final class ScoredDocument {
        private final GameDocument document;
        private final double score;

        public ScoredDocument(GameDocument document, double score) {
            this.document = document;
            this.score = score;
        }
    }

    private static final class Candidate {
        private final GameDocument document;
        private double embeddingScore;
        private double bm25Score;
        private double fusedScore;
        private int embeddingRank;
        private int bm25Rank;

        private Candidate(GameDocument document) {
            this.document = document;
        }
    }
}
