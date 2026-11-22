package com.exai.entity;

import lombok.Getter;

/** 一条带相似度与排名的检索结果。 */
@Getter
public class RetrievedDocument {
    private final GameDocument document;
    /** Raw cosine similarity; retained for compatibility with existing reports. */
    private final double similarity;
    private final double bm25Score;
    private final double fusedScore;
    private final int rank;
    /** One-based branch ranks, or 0 when the document was absent from that branch. */
    private final int embeddingRank;
    private final int bm25Rank;

    public RetrievedDocument(GameDocument document, double similarity, int rank) {
        this(document, similarity, 0.0, 0.0, rank, rank, 0);
    }

    public RetrievedDocument(GameDocument document, double similarity, double bm25Score,
                             double fusedScore, int rank, int embeddingRank, int bm25Rank) {
        this.document = document;
        this.similarity = similarity;
        this.bm25Score = bm25Score;
        this.fusedScore = fusedScore;
        this.rank = rank;
        this.embeddingRank = embeddingRank;
        this.bm25Rank = bm25Rank;
    }
}
