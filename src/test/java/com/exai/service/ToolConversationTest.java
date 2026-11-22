package com.exai.service;

import com.google.gson.*;
import org.junit.Test;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class ToolConversationTest {
    static JsonObject tool(String id, String name) {
        JsonObject m = new JsonObject();
        m.addProperty("role", "assistant");
        JsonObject fn = new JsonObject();
        fn.addProperty("name", name);
        fn.addProperty("arguments", "{}");
        JsonObject call = new JsonObject();
        call.addProperty("id", id);
        call.addProperty("type", "function");
        call.add("function", fn);
        JsonArray calls = new JsonArray();
        calls.add(call);
        m.add("tool_calls", calls);
        return m;
    }

    static class Model extends LLMService {
        int calls;
        int toolsUntil;
        boolean fail;
        JsonObject lastBody;
        Model(int toolsUntil) { super("key", "http://unused", "test", 0); this.toolsUntil = toolsUntil; }
        @Override protected JsonObject requestToolMessage(JsonObject body, ToolStreamListener listener) throws IOException {
            lastBody = body.deepCopy();
            calls++;
            if (fail) throw new IOException("interrupted");
            if (calls <= toolsUntil) return tool("call" + calls, "get_yaml_value");
            JsonObject result = new JsonObject();
            result.addProperty("role", "assistant");
            result.addProperty("content", "finished");
            if (listener != null) listener.onContentDelta("finished");
            return result;
        }
        @Override public String complete(String prompt, double temperature, int maxTokens) { return "summary"; }
    }

    private LLMService.ToolChatResult run(Model model, ToolConversation state, AtomicInteger executions,
                                         LLMService.ToolStreamListener listener, LLMService.Checkpoint checkpoint) {
        return model.runWithTools("system", state, new JsonArray(), (n, a) -> {
            executions.incrementAndGet();
            JsonObject result = new JsonObject(); result.addProperty("value", 42); return result;
        }, listener, 0, 1000, 20, 24000, 8, 4000, checkpoint);
    }

    @Test public void twentiethToolPausesAndResumeDoesNotReplayCompletedTools() {
        Model model = new Model(20);
        ToolConversation state = new ToolConversation();
        state.addText("user", "task");
        AtomicInteger executions = new AtomicInteger();
        assertEquals("PAUSED", run(model, state, executions, null, () -> {}).getStatus());
        assertEquals(20, executions.get());
        assertEquals(20, state.iterations);
        // Simulate persisted state, not merely the same in-memory object.
        state = new Gson().fromJson(new Gson().toJson(state), ToolConversation.class);
        state.pendingInput = "continue";
        assertTrue(run(model, state, executions, null, () -> {}).isOk());
        assertEquals("COMPLETED", state.status);
        assertEquals(20, executions.get());
        assertEquals(21, state.totalIterations);
        assertEquals(1, state.iterations);
    }

    @Test public void finalTextAtTwentiethRequestCompletes() {
        Model model = new Model(19);
        assertEquals("COMPLETED", run(model, new ToolConversation(), new AtomicInteger(), null, () -> {}).getStatus());
        assertEquals(20, model.calls);
    }

    @Test public void streamingAndOrdinaryExecutionHaveSameState() {
        ToolConversation streamed = new ToolConversation();
        ToolConversation ordinary = new ToolConversation();
        AtomicInteger notifications = new AtomicInteger();
        LLMService.ToolStreamListener listener = new LLMService.ToolStreamListener() {
            public void onContentDelta(String text) { assertEquals("finished", text); }
            public void onStatus(String text) { }
            public void onToolCallStarted(String name) { }
            public void onToolCallFinished(String name, JsonObject result) { notifications.incrementAndGet(); }
        };
        run(new Model(3), streamed, new AtomicInteger(), listener, () -> {});
        run(new Model(3), ordinary, new AtomicInteger(), null, () -> {});
        assertEquals(ordinary.messages, streamed.messages);
        assertEquals(3, notifications.get());
    }

    @Test public void diskFailureStopsBeforeToolAndMemoryResultSurvivesFailureAfterTool() {
        Model model = new Model(1);
        ToolConversation state = new ToolConversation();
        AtomicInteger executions = new AtomicInteger();
        run(model, state, executions, null, () -> {
            if (!state.startedCalls.isEmpty()) throw new IllegalStateException("disk failed");
        });
        assertEquals(0, executions.get());
        assertEquals("FAILED", state.status);
        run(model, state, executions, null, () -> {});
        assertEquals(1, executions.get());

        Model other = new Model(1);
        ToolConversation after = new ToolConversation();
        AtomicInteger actual = new AtomicInteger();
        run(other, after, actual, null, () -> {
            if (actual.get() == 1) throw new IllegalStateException("disk failed after tool");
        });
        assertEquals(1, actual.get());
        run(other, after, actual, null, () -> {});
        assertEquals(1, actual.get());
    }

    @Test public void uncertainMutationIsNotReplayed() {
        ToolConversation state = new ToolConversation();
        state.add(tool("mutation", "propose_yaml_set"));
        state.startedCalls.add("mutation");
        AtomicInteger executions = new AtomicInteger();
        run(new Model(0), state, executions, null, () -> {});
        assertEquals(0, executions.get());
        assertTrue(state.messages.get(1).getAsJsonObject().get("content").getAsString().contains("未知"));
    }

    @Test public void networkFailureKeepsCompletedResults() {
        Model model = new Model(1);
        model.fail = true;
        ToolConversation state = new ToolConversation();
        state.addText("user", "original");
        assertFalse(run(model, state, new AtomicInteger(), null, () -> {}).isOk());
        assertEquals(1, state.messages.size());
        assertTrue(state.canContinue());
    }
}
