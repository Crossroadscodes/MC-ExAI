package com.exai.entity;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;
@Setter
@Getter
public class PlayerQuestion {
    private String question;
    private String playerId;
    private String context;
    private Date timestamp;
    public PlayerQuestion(String question){
        this.question = question;
    }
}