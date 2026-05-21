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
    private String createTime;

    public LogEntry(int id, String playerName, String playerInput, String aiResponse,
                    String documentId, String source, String createTime) {
        this.id = id;
        this.playerName = playerName;
        this.playerInput = playerInput;
        this.aiResponse = aiResponse;
        this.documentId = documentId;
        this.source = source;
        this.createTime = createTime;
    }
}
