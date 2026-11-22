package com.exai.retrieval;

import com.exai.entity.GameDocument;
import com.exai.entity.RetrievedDocument;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HybridRankerTest {
    @Test
    public void documentPresentInBothBranchesRanksFirst() {
        GameDocument semanticOnly = document("a");
        GameDocument both = document("b");
        GameDocument lexicalOnly = document("c");

        List<RetrievedDocument> results = HybridRanker.fuse(
                Arrays.asList(scored(semanticOnly, 0.9), scored(both, 0.8)),
                Arrays.asList(scored(both, 4.0), scored(lexicalOnly, 3.0)),
                0.5, 0.5, 60, 3);

        assertEquals(Arrays.asList("b", "a", "c"), Arrays.asList(
                results.get(0).getDocument().getId(),
                results.get(1).getDocument().getId(),
                results.get(2).getDocument().getId()));
        assertEquals(2, results.get(0).getEmbeddingRank());
        assertEquals(1, results.get(0).getBm25Rank());
        assertTrue(results.get(0).getFusedScore() > results.get(1).getFusedScore());
    }

    @Test
    public void bm25OnlyCandidateIsRetainedWithZeroSimilarity() {
        GameDocument lexicalOnly = document("lexical");

        RetrievedDocument result = HybridRanker.fuse(
                Arrays.<HybridRanker.ScoredDocument>asList(),
                Arrays.asList(scored(lexicalOnly, 2.5)),
                0.5, 0.5, 60, 1).get(0);

        assertEquals("lexical", result.getDocument().getId());
        assertEquals(0.0, result.getSimilarity(), 0.000001);
        assertEquals(2.5, result.getBm25Score(), 0.000001);
        assertEquals(0, result.getEmbeddingRank());
        assertEquals(1, result.getBm25Rank());
    }

    private static HybridRanker.ScoredDocument scored(GameDocument document, double score) {
        return new HybridRanker.ScoredDocument(document, score);
    }

    private static GameDocument document(String id) {
        return new GameDocument(id, id, "general", null);
    }
}
