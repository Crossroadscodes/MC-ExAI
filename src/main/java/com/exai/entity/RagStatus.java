package com.exai.entity;

/** 可持久化的 RAG 处理结果，用于日志标注与评估。 */
public enum RagStatus {
    ANSWERED,
    NO_DOCUMENT,
    MODEL_REFUSED,
    MODEL_ERROR,
    NOT_QUESTION,
    UNKNOWN;

    public static RagStatus fromString(String value) {
        if (value == null || value.trim().isEmpty()) {
            return UNKNOWN;
        }
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return UNKNOWN;
        }
    }
}
