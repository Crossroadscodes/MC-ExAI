package com.exai.entity;

import lombok.Getter;

/** 一条带相似度与排名的检索结果。 */
@Getter
public class RetrievedDocument {
    private final GameDocument document;
    private final double similarity;
    private final int rank;

    public RetrievedDocument(GameDocument document, double similarity, int rank) {
        this.document = document;
        this.similarity = similarity;
        this.rank = rank;
    }
}
