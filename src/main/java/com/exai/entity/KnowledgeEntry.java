package com.exai.entity;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class KnowledgeEntry {
    private String question;
    private String answer;
    private String submitter;
    private long timestamp;

    public KnowledgeEntry(String question, String answer, String submitter, long timestamp) {
        this.question = question;
        this.answer = answer;
        this.submitter = submitter;
        this.timestamp = timestamp;
    }
}