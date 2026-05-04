package com.exai.data;

import com.exai.entity.KnowledgeEntry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class KnowledgeQueue {
    private static final List<KnowledgeEntry> pendingQueue = new ArrayList<>();
    private static final Map<KnowledgeEntry, Integer> entryToDbId = new HashMap<>();

    public static void add(KnowledgeEntry entry) {
        pendingQueue.add(entry);
    }

    public static void addWithId(KnowledgeEntry entry, int dbId) {
        pendingQueue.add(entry);
        entryToDbId.put(entry, dbId);
    }

    public static int getDbId(KnowledgeEntry entry) {
        return entryToDbId.getOrDefault(entry, -1);
    }

    public static List<KnowledgeEntry> getAll() {
        return new ArrayList<>(pendingQueue);
    }

    public static List<KnowledgeEntry> getPage(int page, int pageSize) {
        int start = page * pageSize;
        int end = Math.min(start + pageSize, pendingQueue.size());
        if (start >= pendingQueue.size()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(pendingQueue.subList(start, end));
    }

    public static int getTotalPages(int pageSize) {
        return (int) Math.ceil((double) pendingQueue.size() / pageSize);
    }

    public static int getTotalCount() {
        return pendingQueue.size();
    }

    public static void remove(KnowledgeEntry entry) {
        pendingQueue.remove(entry);
        entryToDbId.remove(entry);
    }

    public static boolean removeByIndex(int page, int index, int pageSize) {
        int absoluteIndex = page * pageSize + index;
        if (absoluteIndex >= 0 && absoluteIndex < pendingQueue.size()) {
            KnowledgeEntry entry = pendingQueue.remove(absoluteIndex);
            entryToDbId.remove(entry);
            return true;
        }
        return false;
    }

    public static boolean isDuplicate(String question) {
        String normalized = question.trim().toLowerCase();
        for (KnowledgeEntry entry : pendingQueue) {
            if (entry.getQuestion().trim().toLowerCase().equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    public static void clearAll() {
        pendingQueue.clear();
        entryToDbId.clear();
    }
}