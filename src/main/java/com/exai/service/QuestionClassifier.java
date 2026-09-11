package com.exai.service;

import com.exai.i18n.Lang;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Uses the configured LLM to decide whether input is a game question before RAG retrieval. */
public class QuestionClassifier {
    private static final Pattern JSON_RESULT = Pattern.compile(
            "\\\"isQuestion\\\"\\s*:\\s*(true|false)", Pattern.CASE_INSENSITIVE);

    private final LLMService llmService;

    public QuestionClassifier(LLMService llmService) {
        this.llmService = llmService;
    }

    /** @return true/false for a valid result, or null when it cannot be trusted. */
    public Boolean isQuestion(String input) {
        if (input == null || input.trim().isEmpty()) {
            return false;
        }
        String raw = llmService.complete(Lang.get("question-classifier.prompt", input.trim()), 0.0, 80);
        return parse(raw);
    }

    private Boolean parse(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(normalized)) return true;
        if ("false".equals(normalized)) return false;
        Matcher matcher = JSON_RESULT.matcher(raw);
        return matcher.find() ? Boolean.valueOf(matcher.group(1)) : null;
    }
}
