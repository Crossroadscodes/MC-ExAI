package com.exai.service;

import com.google.gson.*;
import org.junit.Test;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class ConversationContextTest {
    private static String repeat(int count) {
        char[] chars = new char[count]; java.util.Arrays.fill(chars, 'x'); return new String(chars);
    }

    @Test public void summaryPreservesCompleteGroupsAndDoesNotModifyArchive() {
        ToolConversation state = new ToolConversation();
        for (int i = 0; i < 15; i++) {
            state.turnId = i + 1;
            state.addText("user", "request " + i);
            state.add(ToolConversationTest.tool("call" + i, "get_yaml_value"));
            JsonObject result = new JsonObject();
            result.addProperty("role", "tool");
            result.addProperty("tool_call_id", "call" + i);
            result.addProperty("content", repeat(900));
            state.add(result);
        }
        JsonArray original = state.messages.deepCopy();
        AtomicInteger summaries = new AtomicInteger();
        JsonArray request = ConversationContext.prepare(state, "system", 5000, 4, 800,
                prompt -> { summaries.incrementAndGet(); return "goal, completed work, next step"; });
        assertTrue(summaries.get() > 0);
        assertTrue(state.summaryThrough > 0);
        assertEquals(original, state.messages);
        assertTrue(request.toString().length() < 5100);
        assertFalse(request.toString().contains("_turnId"));
        Set<String> pending = new HashSet<>();
        for (JsonElement el : request) {
            JsonObject m = el.getAsJsonObject();
            if (m.has("tool_calls")) for (JsonElement call : m.getAsJsonArray("tool_calls"))
                pending.add(call.getAsJsonObject().get("id").getAsString());
            if ("tool".equals(m.get("role").getAsString()))
                assertTrue(pending.remove(m.get("tool_call_id").getAsString()));
        }
        assertTrue(pending.isEmpty());
        int before = summaries.get();
        ConversationContext.prepare(state, "system", 5000, 4, 800, prompt -> { summaries.incrementAndGet(); return "new"; });
        assertEquals(before, summaries.get());
    }

    @Test public void failedSummaryIsExplicitAndArchiveRemainsAvailable() {
        ToolConversation state = new ToolConversation();
        state.addText("user", repeat(6000));
        state.addText("assistant", "old answer");
        state.addText("user", "new request");
        ConversationContext.prepare(state, "sys", 4000, 1, 600, prompt -> null);
        assertTrue(state.summary.contains("部分历史未注入"));
        assertEquals(6000, state.messages.get(0).getAsJsonObject().get("content").getAsString().length());
    }

    @Test public void hugeToolResultIsMarkedWithoutTruncatingDiskRecord() {
        ToolConversation state = new ToolConversation();
        state.add(ToolConversationTest.tool("a", "get_yaml_value"));
        JsonObject result = new JsonObject();
        result.addProperty("role", "tool"); result.addProperty("tool_call_id", "a");
        result.addProperty("content", repeat(30000)); state.add(result);
        JsonArray request = ConversationContext.prepare(state, "sys", 4000, 8, 600, prompt -> "summary");
        assertTrue(request.toString().contains("context_truncated"));
        assertEquals(30000, state.messages.get(1).getAsJsonObject().get("content").getAsString().length());
    }

    @Test(expected = IllegalArgumentException.class)
    public void oversizedCurrentUserInputIsRejectedInsteadOfTruncated() {
        ToolConversation state = new ToolConversation(); state.addText("user", repeat(6000));
        ConversationContext.prepare(state, "sys", 4000, 8, 600, prompt -> "summary");
    }
}
