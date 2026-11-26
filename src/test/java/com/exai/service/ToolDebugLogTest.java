package com.exai.service;

import com.exai.config.Config;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class ToolDebugLogTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private File root;
    private boolean previousDebug;

    @Before public void setup() {
        root = temp.getRoot();
        previousDebug = Config.webAiDebug;
        ToolDebugLog.useDirectoryForTest(root);
        Config.webAiDebug = true;
    }

    @After public void teardown() {
        Config.webAiDebug = previousDebug;
        ToolDebugLog.useDirectoryForTest(null);
    }

    private List<String> events() throws IOException {
        File[] logs = new File(root, "logs/tool-debug").listFiles();
        assertNotNull(logs);
        assertEquals(1, logs.length);
        return Files.readAllLines(logs[0].toPath(), StandardCharsets.UTF_8);
    }

    private JsonObject call() {
        return ToolConversationTest.tool("call1", "get_yaml_value").getAsJsonArray("tool_calls").get(0).getAsJsonObject();
    }

    private LLMService.ToolChatResult run(ToolConversation state, LLMService.ToolExecutor executor) {
        return new ToolConversationTest.Model(1).runWithTools("system", state, new JsonArray(), executor,
                null, 0, 1000, 20, 24000, 8, 4000, () -> {});
    }

    @Test public void debugDefaultsToDisabledAndDoesNotCreateDirectories() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
        assertTrue(yaml.contains("webui.ai.debug"));
        assertFalse(yaml.getBoolean("webui.ai.debug"));
        Config.webAiDebug = false;
        assertNull(ToolDebugLog.start("test", new ToolConversation(), call(), false));
        ToolDebugLog.taskFailed("test", new ToolConversation(), new IOException("secret"));
        assertFalse(new File(root, "logs").exists());
    }

    @Test public void logsCorrelatedStartFinishAndRedactsValuesAndResultContent() throws Exception {
        ToolConversation state = new ToolConversation();
        state.taskId = "task"; state.turnId = 7; state.iterations = 2;
        JsonObject call = call();
        call.getAsJsonObject("function").addProperty("arguments",
                "{\"file\":\"Example/config.yml\",\"path\":\"database.password\",\"value\":\"secret-value\",\"keyword\":\"secret-keyword\",\"password\":\"secret-password\"}");
        ToolDebugLog.Call trace = ToolDebugLog.start("model", state, call, false);
        JsonObject message = new JsonObject();
        message.addProperty("content", "{\"ok\":true,\"value\":\"secret-result\",\"results\":[\"secret-preview\"],\"truncated\":true}");
        trace.finish(message);
        List<String> lines = events();
        assertEquals(2, lines.size());
        JsonObject start = JsonParser.parseString(lines.get(0)).getAsJsonObject();
        JsonObject finish = JsonParser.parseString(lines.get(1)).getAsJsonObject();
        assertEquals("tool_start", start.get("event").getAsString());
        assertEquals("tool_finish", finish.get("event").getAsString());
        assertEquals(start.get("executionId"), finish.get("executionId"));
        assertEquals("task", start.get("taskId").getAsString());
        assertEquals(7, start.get("turnId").getAsInt());
        assertEquals("call1", start.get("callId").getAsString());
        assertEquals("Example/config.yml", start.getAsJsonObject("arguments").get("file").getAsString());
        assertEquals("[redacted]", start.getAsJsonObject("arguments").get("value").getAsString());
        assertEquals(1, finish.get("resultsCount").getAsInt());
        assertTrue(finish.get("ok").getAsBoolean());
        assertTrue(finish.get("durationMs").getAsLong() >= 0);
        for (String line : lines) assertFalse(line.contains("secret-"));
    }

    @Test public void executorFailureIsLoggedWithoutExceptionMessage() throws Exception {
        ToolConversation state = new ToolConversation();
        assertTrue(run(state, (name, args) -> { throw new IllegalStateException("secret-exception"); }).isOk());
        JsonObject finish = JsonParser.parseString(events().get(1)).getAsJsonObject();
        assertTrue(finish.get("hasError").getAsBoolean());
        assertEquals("IllegalStateException", finish.get("errorType").getAsString());
        assertFalse(events().toString().contains("secret-exception"));
    }

    @Test public void disablingDebugStopsWritesAndPreservesExistingLogs() throws Exception {
        ToolDebugLog.Call trace = ToolDebugLog.start("test", new ToolConversation(), call(), false);
        Config.webAiDebug = false;
        JsonObject result = new JsonObject(); result.addProperty("content", "{\"ok\":true}");
        trace.finish(result);
        assertNull(ToolDebugLog.start("test", new ToolConversation(), call(), false));
        assertEquals(1, events().size());
    }

    @Test public void malformedArgumentsAreDiagnosedWithoutRawText() throws Exception {
        JsonObject call = call();
        call.getAsJsonObject("function").addProperty("arguments", "secret-invalid-json");
        ToolDebugLog.start("test", new ToolConversation(), call, false);
        assertTrue(JsonParser.parseString(events().get(0)).getAsJsonObject().getAsJsonObject("arguments").has("parseError"));
        assertFalse(events().toString().contains("secret-invalid-json"));
    }

    @Test public void uncertainWritesAreLoggedWithoutReexecution() throws Exception {
        ToolConversation state = new ToolConversation();
        state.add(ToolConversationTest.tool("pending", "propose_yaml_set"));
        state.startedCalls.add("pending");
        AtomicInteger writes = new AtomicInteger();
        assertTrue(run(state, (name, args) -> { writes.incrementAndGet(); return new JsonObject(); }).isOk());
        JsonObject start = JsonParser.parseString(events().get(0)).getAsJsonObject();
        JsonObject finish = JsonParser.parseString(events().get(1)).getAsJsonObject();
        assertTrue(start.get("recovering").getAsBoolean());
        assertTrue(finish.get("hasError").getAsBoolean());
        assertEquals(1, writes.get());
    }

    @Test public void loggingFailureDoesNotStopToolExecution() throws Exception {
        Files.write(new File(root, "logs").toPath(), new byte[]{1});
        AtomicInteger executions = new AtomicInteger();
        assertTrue(run(new ToolConversation(), (name, args) -> {
            executions.incrementAndGet(); return new JsonObject();
        }).isOk());
        assertEquals(1, executions.get());
    }

    @Test public void taskFailureOnlyLogsExceptionType() throws Exception {
        LLMService.ToolChatResult result = new ToolConversationTest.Model(0).runWithTools("system", new ToolConversation(),
                new JsonArray(), null, null, 0, 1000, 20, 24000, 8, 4000,
                () -> { throw new IllegalStateException("secret-checkpoint"); });
        assertFalse(result.isOk());
        String line = events().get(0);
        JsonObject event = JsonParser.parseString(line).getAsJsonObject();
        assertEquals("task_error", event.get("event").getAsString());
        assertEquals("IllegalStateException", event.get("errorType").getAsString());
        assertFalse(line.contains("secret-checkpoint"));
    }
}
