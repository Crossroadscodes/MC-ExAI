package com.exai.retrieval;

import com.exai.entity.GameDocument;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class Bm25IndexTest {
    @Test
    public void tokenizesChineseBigramsAndAsciiTerms() {
        assertEquals(Arrays.asList("钻石", "石怎", "怎么", "么卖", "shop_2"),
                Bm25Index.tokenize("钻石怎么卖 /SHOP_2"));
    }

    @Test
    public void ranksMatchingDocumentFirst() {
        GameDocument diamond = document("diamond", "问题：钻石怎么出售。答案：前往矿物商店。");
        GameDocument apple = document("apple", "问题：苹果怎么获得。答案：完成农场任务。");
        Bm25Index index = new Bm25Index(Arrays.asList(apple, diamond));

        assertEquals("diamond", index.search("钻石出售", 10).get(0).getDocument().getId());
        assertTrue(index.search("钻石出售", 10).get(0).getScore() > 0.0);
    }

    @Test
    public void returnsNoCandidateWithoutLexicalOverlap() {
        Bm25Index index = new Bm25Index(Collections.singletonList(
                document("diamond", "钻石可以在矿物商店出售")));

        assertTrue(index.search("公会领地权限", 10).isEmpty());
    }

    private static GameDocument document(String id, String content) {
        return new GameDocument(id, content, "general", null);
    }
}
