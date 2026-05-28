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
    /** AI 判定无法作答（命中拒答标记）时为 true，公屏广播据此跳过不回复。 */
    private boolean unknown;
}