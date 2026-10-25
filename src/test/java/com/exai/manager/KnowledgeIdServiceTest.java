package com.exai.manager;

import com.exai.entity.KnowledgeEntry;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class KnowledgeIdServiceTest {
    @Test
    public void fallbackDocumentIdIsStableAndContentBased() {
        KnowledgeEntry first = new KnowledgeEntry("金币怎么获得？", "完成任务。", "", 0L);
        KnowledgeEntry same = new KnowledgeEntry("金币怎么获得？", "完成任务。", "", 1L);
        KnowledgeEntry changed = new KnowledgeEntry("金币怎么获得？", "出售物品。", "", 0L);

        assertEquals(KnowledgeIdService.documentId(first), KnowledgeIdService.documentId(same));
        assertTrue(KnowledgeIdService.documentId(first).startsWith("legacy_"));
        assertNotEquals(KnowledgeIdService.documentId(first), KnowledgeIdService.documentId(changed));
    }

    @Test
    public void persistedSemanticIdWinsOverFallback() {
        KnowledgeEntry entry = new KnowledgeEntry("金币怎么获得？", "完成任务。", "", 0L);
        entry.setKnowledgeId("economy_get_coin");
        assertEquals("economy_get_coin", KnowledgeIdService.documentId(entry));
    }
}
