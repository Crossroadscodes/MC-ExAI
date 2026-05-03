package com.exai.entity;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
@Setter
@Getter
public class Answer {
    private String answer;
    private List<String> sources;
    private double confidence;
    private String suggestedAction;
}