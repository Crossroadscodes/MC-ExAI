package com.exai.entity;

import lombok.Getter;

import java.util.Collections;
import java.util.List;

/** 一次完整 RAG 调用的答案、检索轨迹与耗时。 */
@Getter
public class RagResult {
    private final Answer answer;
    private final List<RetrievedDocument> documents;
    private final long retrievalMillis;
    private final long generationMillis;

    public RagResult(Answer answer, List<RetrievedDocument> documents,
                     long retrievalMillis, long generationMillis) {
        this.answer = answer;
        this.documents = documents == null ? Collections.emptyList() : documents;
        this.retrievalMillis = retrievalMillis;
        this.generationMillis = generationMillis;
    }
}
