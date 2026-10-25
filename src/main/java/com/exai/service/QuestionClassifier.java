package com.exai.service;

import com.exai.ExAI;
import com.exai.i18n.Lang;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Uses the configured LLM to decide whether input is a game question before RAG retrieval. */
public class QuestionClassifier {
    public enum Result {
        QUESTION,
        NOT_QUESTION,
        UNAVAILABLE
    }

    private static final Pattern JSON_RESULT = Pattern.compile(
            "\\\"isQuestion\\\"\\s*:\\s*(true|false)", Pattern.CASE_INSENSITIVE);
    private static final int MAX_LOGGED_RESPONSE_CHARS = 300;

    private final LLMService llmService;

    public QuestionClassifier(LLMService llmService) {
        this.llmService = llmService;
    }

    /** Returns a non-null three-state result so callers cannot accidentally fail open. */
    public Result classify(String input) {
        if (input == null || input.trim().isEmpty()) {
            return Result.NOT_QUESTION;
        }
        if (llmService == null || !llmService.isConfigured()) {
            warn("Question classification unavailable: LLM is not configured");
            return Result.UNAVAILABLE;
        }
        String raw = llmService.complete(Lang.get("question-classifier.prompt", input.trim()), 0.0, 80);
        Result result = parse(raw);
        if (result == Result.UNAVAILABLE) {
            warn("Question classification returned an unusable response: " + summarize(raw));
        }
        return result;
    }

    static Result parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return Result.UNAVAILABLE;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(normalized)) return Result.QUESTION;
        if ("false".equals(normalized)) return Result.NOT_QUESTION;
        Matcher matcher = JSON_RESULT.matcher(raw);
        if (!matcher.find()) {
            return Result.UNAVAILABLE;
        }
        return Boolean.parseBoolean(matcher.group(1)) ? Result.QUESTION : Result.NOT_QUESTION;
    }

    private static String summarize(String raw) {
        if (raw == null) {
            return "<null>";
        }
        String oneLine = raw.replace('\r', ' ').replace('\n', ' ').trim();
        if (oneLine.isEmpty()) {
            return "<empty>";
        }
        return oneLine.length() <= MAX_LOGGED_RESPONSE_CHARS
                ? oneLine : oneLine.substring(0, MAX_LOGGED_RESPONSE_CHARS) + "...";
    }

    private static void warn(String message) {
        try {
            if (ExAI.getInstance() != null) {
                ExAI.getInstance().getLogger().warning(message);
                return;
            }
        } catch (Exception ignored) {
        }
        System.err.println(message);
    }
}
