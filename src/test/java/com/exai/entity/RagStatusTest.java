package com.exai.entity;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class RagStatusTest {
    @Test
    public void parsesPersistedValuesSafely() {
        assertEquals(RagStatus.NO_DOCUMENT, RagStatus.fromString("no_document"));
        assertEquals(RagStatus.ANSWERED, RagStatus.fromString("ANSWERED"));
        assertEquals(RagStatus.UNKNOWN, RagStatus.fromString("future_status"));
        assertEquals(RagStatus.UNKNOWN, RagStatus.fromString(null));
    }
}
