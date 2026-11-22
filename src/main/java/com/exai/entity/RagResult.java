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
    private final AnswerabilityTrace answerability;

    public RagResult(Answer answer, List<RetrievedDocument> documents,
                     long retrievalMillis, long generationMillis) {
        this(answer, documents, retrievalMillis, generationMillis, null);
    }

    public RagResult(Answer answer, List<RetrievedDocument> documents,
                     long retrievalMillis, long generationMillis,
                     AnswerabilityTrace answerability) {
        this.answer = answer;
        this.documents = documents == null ? Collections.emptyList() : documents;
        this.retrievalMillis = retrievalMillis;
        this.generationMillis = generationMillis;
        this.answerability = answerability;
    }

    @Getter
    public static class AnswerabilityTrace {
        private final boolean answerable;
        private final String parseStatus;
        private final String rawResponse;
        private final String evidence;
        private final String reason;

        public AnswerabilityTrace(boolean answerable, String parseStatus, String rawResponse,
                                  String evidence, String reason) {
            this.answerable = answerable;
            this.parseStatus = parseStatus;
            this.rawResponse = rawResponse;
            this.evidence = evidence;
            this.reason = reason;
        }
    }
}
