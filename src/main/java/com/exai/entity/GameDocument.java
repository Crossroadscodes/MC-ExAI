package com.exai.entity;

import java.util.Map;
import lombok.Getter;
import lombok.Setter;
@Setter
@Getter
public class GameDocument {
    private String id;
    private String content;
    private String category;
    private double[] embedding;

    public GameDocument(String id, String content, String category, Object o) {
        this.id = id;
        this.content = content;
        this.category = category;
    }
}