package com.exai.evaluation;

import com.exai.data.DataContainer;

import java.util.Collections;
import java.util.List;

/** Routes the RAG test set to the active YAML or MySQL storage backend. */
public final class EvaluationTestStore {
    private EvaluationTestStore() {}

    public static List<EvaluationCase> readAll() {
        return DataContainer.storage == null
                ? Collections.emptyList()
                : DataContainer.storage.readEvaluationCases();
    }

    public static EvaluationCase findByLogFingerprint(String fingerprint) {
        for (EvaluationCase item : readAll()) {
            if (fingerprint != null && fingerprint.equals(item.getSourceLogFingerprint())) {
                return item;
            }
        }
        return null;
    }

    public static EvaluationCase upsert(String sourceFingerprint, EvaluationCase input) {
        if (DataContainer.storage == null) {
            throw new IllegalStateException("Storage is not initialized");
        }
        return DataContainer.storage.upsertEvaluationCase(sourceFingerprint, input);
    }

    public static boolean delete(String caseId) {
        return DataContainer.storage != null
                && DataContainer.storage.deleteEvaluationCase(caseId);
    }

    public static int replaceKnowledgeId(String oldId, String newId) {
        return DataContainer.storage == null
                ? 0 : DataContainer.storage.replaceEvaluationKnowledgeId(oldId, newId);
    }
}
