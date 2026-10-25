package com.exai.evaluation;

import com.exai.entity.LogEntry;
import com.exai.entity.RagStatus;
import com.exai.i18n.Lang;
import com.exai.manager.KnowledgeFileManager;
import com.exai.manager.PluginDescriptionManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 游戏内书本标注格式的生成与解析。 */
public final class EvaluationAnnotationService {
    private EvaluationAnnotationService() {}

    public static List<String> buildPages(LogEntry log) {
        EvaluationCase draft = createDraft(log);
        String type = draft.getExpectedBehavior() == null
                ? "REVIEW" : draft.getExpectedBehavior().name();
        String meta = Lang.get("book.rag-enabled-prefix") + draft.isEnabled() + "\n"
                + Lang.get("book.rag-type-prefix") + type + "\n"
                + Lang.get("book.rag-ids-prefix")
                + String.join(",", draft.getExpectedKnowledgeIds());
        return Arrays.asList(meta,
                Lang.get("book.pattern-prefix-q") + draft.getQuestion(),
                Lang.get("book.pattern-prefix-a") + draft.getReferenceAnswer());
    }

    /** Creates an editable copy without mutating an already saved test case. */
    public static EvaluationCase createDraft(LogEntry log) {
        EvaluationCase existing = EvaluationTestStore.findByLogFingerprint(logFingerprint(log));
        if (existing != null) {
            return copyOf(existing);
        }

        EvaluationCase draft = new EvaluationCase();
        RagStatus status = log.getRagStatus();
        ExpectedBehavior behavior = null;
        if (status == RagStatus.ANSWERED) behavior = ExpectedBehavior.ANSWER;
        else if (status == RagStatus.NO_DOCUMENT || status == RagStatus.MODEL_REFUSED) {
            behavior = ExpectedBehavior.REFUSE;
        }
        draft.setEnabled(true);
        draft.setQuestion(log.getPlayerInput());
        draft.setExpectedBehavior(behavior);
        draft.setReferenceAnswer(behavior == ExpectedBehavior.ANSWER ? log.getAiResponse() : "");
        draft.setExpectedKnowledgeIds(behavior == ExpectedBehavior.ANSWER
                ? normalizeLoggedIds(log.getDocumentId()) : new ArrayList<>());
        return draft;
    }

    public static EvaluationCase copyOf(EvaluationCase source) {
        EvaluationCase copy = new EvaluationCase();
        copy.setId(source.getId());
        copy.setEnabled(source.isEnabled());
        copy.setSourceLogFingerprint(source.getSourceLogFingerprint());
        copy.setQuestion(source.getQuestion());
        copy.setExpectedBehavior(source.getExpectedBehavior());
        copy.setReferenceAnswer(source.getReferenceAnswer());
        copy.setExpectedKnowledgeIds(source.getExpectedKnowledgeIds() == null
                ? new ArrayList<>() : new ArrayList<>(source.getExpectedKnowledgeIds()));
        copy.setUpdatedAt(source.getUpdatedAt());
        return copy;
    }

    public static EvaluationCase parse(String content) {
        String enabledValue = field(content, Lang.get("book.rag-enabled-prefix"));
        String typeValue = field(content, Lang.get("book.rag-type-prefix"));
        String idsValue = field(content, Lang.get("book.rag-ids-prefix"));
        String qPrefix = Lang.get("book.pattern-prefix-q");
        String aPrefix = Lang.get("book.pattern-prefix-a");
        int qAt = content.indexOf(qPrefix);
        int aAt = content.indexOf(aPrefix, qAt < 0 ? 0 : qAt + qPrefix.length());
        if (qAt < 0 || aAt < 0) return null;
        String question = content.substring(qAt + qPrefix.length(), aAt).trim();
        String answer = content.substring(aAt + aPrefix.length()).trim();
        ExpectedBehavior behavior;
        try {
            behavior = ExpectedBehavior.valueOf(typeValue.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (Exception e) {
            return null;
        }
        List<String> ids = splitIds(idsValue);
        EvaluationCase result = new EvaluationCase();
        result.setEnabled(!"false".equalsIgnoreCase(enabledValue.trim()));
        result.setQuestion(question);
        result.setExpectedBehavior(behavior);
        result.setReferenceAnswer(answer);
        result.setExpectedKnowledgeIds(ids);
        return isValid(result) ? result : null;
    }

    public static boolean isValid(EvaluationCase item) {
        if (item == null || item.getQuestion() == null || item.getQuestion().trim().isEmpty()
                || item.getExpectedBehavior() == null) {
            return false;
        }
        String answer = item.getReferenceAnswer() == null ? "" : item.getReferenceAnswer().trim();
        List<String> ids = item.getExpectedKnowledgeIds() == null
                ? new ArrayList<>() : item.getExpectedKnowledgeIds();
        if (item.getExpectedBehavior() == ExpectedBehavior.ANSWER
                && (answer.isEmpty() || ids.isEmpty())) {
            return false;
        }
        if (item.getExpectedBehavior() == ExpectedBehavior.REFUSE
                && (!answer.isEmpty() || !ids.isEmpty())) {
            return false;
        }
        return item.getExpectedBehavior() != ExpectedBehavior.ANSWER
                || knownKnowledgeIds().containsAll(ids);
    }

    public static List<String> parseKnowledgeIds(String value) {
        return splitIds(value);
    }

    private static Set<String> knownKnowledgeIds() {
        Set<String> known = new HashSet<>();
        KnowledgeFileManager.readAll().forEach(entry -> {
            if (entry.getKnowledgeId() != null) known.add(entry.getKnowledgeId());
        });
        PluginDescriptionManager.readAll().forEach(entry -> {
            if (entry.isEnabled() && entry.getDescription() != null
                    && !entry.getDescription().trim().isEmpty()) {
                known.add("plugin_" + entry.getName());
            }
        });
        return known;
    }

    private static String field(String content, String prefix) {
        int at = content.indexOf(prefix);
        if (at < 0) return "";
        int end = content.indexOf('\n', at);
        return content.substring(at + prefix.length(), end < 0 ? content.length() : end).trim();
    }

    private static List<String> splitIds(String value) {
        List<String> out = new ArrayList<>();
        if (value == null) return out;
        for (String part : value.split("[,，\\s]+")) {
            String id = part.trim();
            if (!id.isEmpty() && !id.startsWith("legacy_") && !out.contains(id)) out.add(id);
        }
        return out;
    }

    /** 兼容旧日志的 chunk_0001 顺序编号；能安全映射时替换为当前稳定 ID。 */
    private static List<String> normalizeLoggedIds(String value) {
        List<String> raw = splitIds(value);
        List<String> knowledgeIds = new ArrayList<>();
        for (com.exai.entity.KnowledgeEntry entry : KnowledgeFileManager.readAll()) {
            knowledgeIds.add(entry.getKnowledgeId());
        }
        List<String> out = new ArrayList<>();
        for (String id : raw) {
            String normalized = id;
            if (id.matches("chunk_[0-9]{4,}")) {
                try {
                    int index = Integer.parseInt(id.substring("chunk_".length())) - 1;
                    normalized = index >= 0 && index < knowledgeIds.size() ? knowledgeIds.get(index) : null;
                } catch (NumberFormatException ignored) {
                    normalized = null;
                }
            }
            if (normalized != null && !normalized.trim().isEmpty()
                    && !normalized.startsWith("legacy_") && !out.contains(normalized)) {
                out.add(normalized);
            }
        }
        return out;
    }

    public static String logFingerprint(LogEntry log) {
        String text = log.getId() + "\u0000" + safe(log.getSource()) + "\u0000"
                + safe(log.getCreateTime()) + "\u0000" + safe(log.getPlayerInput());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : digest) out.append(String.format("%02x", b & 0xff));
            return out.toString();
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode());
        }
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
