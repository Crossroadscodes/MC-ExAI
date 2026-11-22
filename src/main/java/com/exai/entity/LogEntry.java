package com.exai.entity;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LogEntry {
    private int id;
    private String playerName;
    private String playerInput;
    private String aiResponse;
    private String documentId;
    private String source;
    private RagStatus ragStatus;
    private String createTime;
    private String rawContext;
    private String retrievalQuestion = "";

    public LogEntry(int id, String playerName, String playerInput, String aiResponse,
                    String documentId, String source, RagStatus ragStatus, String createTime) {
        this(id, playerName, playerInput, aiResponse, documentId, source, ragStatus, createTime, "");
    }

    public LogEntry(int id, String playerName, String playerInput, String aiResponse,
                    String documentId, String source, RagStatus ragStatus, String createTime, String rawContext) {
        this.id = id;
        this.playerName = playerName;
        this.playerInput = playerInput;
        this.aiResponse = aiResponse;
        this.documentId = documentId;
        this.source = source;
        this.ragStatus = ragStatus == null ? RagStatus.UNKNOWN : ragStatus;
        this.createTime = createTime;
        this.rawContext = rawContext == null ? "" : rawContext;
    }
}
