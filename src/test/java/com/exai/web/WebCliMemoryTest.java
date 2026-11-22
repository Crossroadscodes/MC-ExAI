package com.exai.web;

import com.exai.config.Config;
import com.exai.service.LLMService;
import com.exai.service.ToolConversation;
import com.google.gson.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.IOException;
import java.util.Map;
import static org.junit.Assert.*;

public class WebCliMemoryTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private LLMService previous;
    private int limit;
    private Model model;
    @Before public void setup() {
        WebAiSessionStore.useDirectory(temp.getRoot());
        previous = Config.llm; limit = Config.webAiMaxIterations;
        model = new Model(); Config.llm = model; Config.webAiMaxIterations = 2;
    }
    @After public void teardown() {
        Config.llm = previous; Config.webAiMaxIterations = limit;
        WebAiSessionStore.useDirectory(null);
    }

    static class Model extends LLMService {
        int requests;
        boolean answer;
        Model() { super("configured-test-key", "http://unused", "test", 0); }
        @Override protected JsonObject requestToolMessage(JsonObject body, ToolStreamListener listener) throws IOException {
            requests++;
            JsonObject message = new JsonObject(); message.addProperty("role", "assistant");
            if (answer) {
                message.addProperty("content", "done");
                if (listener != null) listener.onContentDelta("done");
            } else {
                JsonObject fn = new JsonObject(); fn.addProperty("name", "unknown_tool"); fn.addProperty("arguments", "{}");
                JsonObject call = new JsonObject(); call.addProperty("id", "call" + requests); call.add("function", fn);
                JsonArray calls = new JsonArray(); calls.add(call); message.add("tool_calls", calls);
            }
            return message;
        }
    }

    @Test public void explicitContinueUsesSameTaskAndOrdinaryInputReplacesIt() {
        Map<String, Object> paused = WebCli.exec("first task", "Example", "session");
        assertEquals(true, paused.get("ok")); assertEquals("PAUSED", paused.get("taskStatus"));
        assertEquals(true, paused.get("canContinue"));
        String task = paused.get("taskId").toString();
        model.answer = true;
        Map<String, Object> continued = WebCli.exec("继续", "Other", "session");
        assertEquals("COMPLETED", continued.get("taskStatus"));
        assertEquals(task, continued.get("taskId"));
        assertEquals(3, continued.get("totalIterations"));
        assertEquals("Example", WebAiSessionStore.execution("session").cwd);
        assertEquals(2, WebAiSessionStore.size("session"));
        assertEquals(false, WebCli.exec("/continue", "", "session").get("ok"));

        model.answer = false;
        Map<String, Object> next = WebCli.exec("second task", "New", "session");
        String previousTask = next.get("taskId").toString();
        model.answer = true;
        Map<String, Object> replacement = WebCli.exec("new request", "New", "session");
        assertNotEquals(previousTask, replacement.get("taskId"));
        assertEquals(1, replacement.get("totalIterations"));
        assertTrue(WebAiSessionStore.execution("session").messages.toString().contains("旧暂停任务结束"));
    }

    @Test public void streamedPauseIsDoneAndRestoredAfterRestart() throws Exception {
        Sink sink = new Sink();
        WebCli.execStream("task", "", "streamed", sink);
        assertNull(sink.error); assertEquals("PAUSED", sink.done.get("taskStatus"));
        assertTrue(sink.text.toString().contains("2 轮"));
        WebAiSessionStore.useDirectory(temp.getRoot());
        assertEquals(true, WebAiSessionStore.transcript("streamed").get("canContinue"));
        model.answer = true;
        WebCli.execStream("/continue", "", "streamed", new Sink());
        assertEquals("COMPLETED", WebAiSessionStore.execution("streamed").status);
    }

    @Test public void resetClearsTaskAndRefreshesResponseMetadata() {
        WebCli.exec("task", "", "reset");
        Map<String, Object> result = WebCli.exec("/reset", "", "reset");
        assertEquals(false, result.get("canContinue"));
        assertEquals(0, WebAiSessionStore.size("reset"));
        ToolConversation state = WebAiSessionStore.execution("reset");
        assertEquals(0, state.messages.size()); assertEquals("", state.summary);
    }

    static class Sink implements WebCli.StreamSink {
        Map<String, Object> done, error;
        StringBuilder text = new StringBuilder();
        public void meta(Map<String, Object> data) { }
        public void status(String message) { }
        public void delta(String value) { text.append(value); }
        public void done(Map<String, Object> data) { done = data; }
        public void error(Map<String, Object> data) { error = data; }
    }
}
