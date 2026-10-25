package com.exai.evaluation;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class EvaluationCase {
    private String id;
    private boolean enabled = true;
    private String sourceLogFingerprint;
    private String question;
    private ExpectedBehavior expectedBehavior;
    private String referenceAnswer;
    private List<String> expectedKnowledgeIds = new ArrayList<>();
    private long updatedAt;
}
