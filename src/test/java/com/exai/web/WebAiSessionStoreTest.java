package com.exai.web;

import com.exai.service.ToolConversation;
import com.google.gson.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public class WebAiSessionStoreTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private File root;
    @Before public void setup() { root = temp.getRoot(); WebAiSessionStore.useDirectory(root); }
    @After public void teardown() { WebAiSessionStore.useDirectory(null); }
    private Path file(String id) { return new File(root, "web-ai-sessions/" + id + ".json").toPath(); }
    private void append(String id, String user, String assistant) {
        long turn = WebAiSessionStore.beginTurn(id);
        WebAiSessionStore.appendExchange(id, turn, user, assistant);
    }

    @Test public void fullTranscriptAndExecutionSurviveRestart() {
        char[] chars = new char[9000]; java.util.Arrays.fill(chars, 'x');
        String content = new String(chars);
        append("one", content, content);
        ToolConversation state = WebAiSessionStore.execution("one");
        state.status = "RUNNING"; state.taskId = "task"; state.turnId = 1;
        state.addText("user", content); WebAiSessionStore.checkpoint("one");
        WebAiSessionStore.useDirectory(root);
        assertEquals("INTERRUPTED", WebAiSessionStore.execution("one").status);
        assertEquals(content, WebAiSessionStore.execution("one").messages.get(0).getAsJsonObject().get("content").getAsString());
        List<?> messages = (List<?>) WebAiSessionStore.transcript("one").get("messages");
        assertEquals(content, ((Map<?, ?>) messages.get(0)).get("content"));
    }

    @Test public void corruptedPrimaryRestoresBackupAndPreservesCorruptEvidence() throws Exception {
        append("one", "first", "answer");
        append("one", "second", "answer");
        Files.write(file("one"), "{".getBytes(StandardCharsets.UTF_8));
        WebAiSessionStore.useDirectory(root);
        assertEquals(1, WebAiSessionStore.size("one"));
        WebAiSessionStore.checkpoint("one");
        assertTrue(Files.exists(file("one").resolveSibling("one.json.corrupt")));
        assertEquals(1, WebAiSessionStore.size("one"));
    }

    @Test public void bothCorruptFilesAreNotOverwritten() throws Exception {
        append("one", "first", "answer"); append("one", "second", "answer");
        Files.write(file("one"), "bad".getBytes(StandardCharsets.UTF_8));
        Files.write(AtomicJsonFile.backup(file("one")), "bad".getBytes(StandardCharsets.UTF_8));
        WebAiSessionStore.useDirectory(root);
        try { WebAiSessionStore.ensure("one"); fail(); } catch (IllegalStateException expected) { }
        assertEquals("bad", new String(Files.readAllBytes(file("one")), StandardCharsets.UTF_8));
    }

    @Test public void migrationIsIdempotentAndResetDoesNotReimportLegacy() throws Exception {
        JsonObject session = new JsonObject(); session.addProperty("id", "legacy");
        session.addProperty("lastConversation", System.currentTimeMillis());
        session.addProperty("lastAccess", System.currentTimeMillis());
        JsonArray messages = new JsonArray();
        for (String role : new String[]{"user", "assistant"}) {
            JsonObject m = new JsonObject(); m.addProperty("role", role); m.addProperty("content", role);
            m.addProperty("turnId", 7); messages.add(m);
        }
        session.add("messages", messages); JsonArray sessions = new JsonArray(); sessions.add(session);
        JsonObject legacy = new JsonObject(); legacy.add("sessions", sessions);
        Files.write(new File(root, "web-ai-sessions.json").toPath(), legacy.toString().getBytes(StandardCharsets.UTF_8));
        assertEquals(1, WebAiSessionStore.size("legacy"));
        assertEquals(2, WebAiSessionStore.execution("legacy").messages.size());
        WebAiSessionStore.useDirectory(root);
        assertEquals(1, WebAiSessionStore.size("legacy"));
        WebAiSessionStore.clearAll(); WebAiSessionStore.useDirectory(root);
        assertEquals(0, WebAiSessionStore.size("legacy"));
    }

    @Test public void rewindInvalidatesSummaryAndRemovesFutureLedger() {
        append("one", "first", "answer"); append("one", "second", "answer");
        ToolConversation state = WebAiSessionStore.execution("one");
        state.turnId = 1; state.addText("user", "first");
        state.turnId = 2; state.addText("user", "second");
        state.summary = "future secret"; state.summaryThrough = 2; state.status = "PAUSED";
        WebAiSessionStore.checkpoint("one");
        assertTrue(WebAiSessionStore.rewindFrom("one", 2).isOk());
        ToolConversation remaining = WebAiSessionStore.execution("one");
        assertEquals(1, remaining.messages.size()); assertEquals("", remaining.summary);
        assertFalse(remaining.canContinue());
    }

    @Test public void writeFailureRetainsMemoryAndDoesNotTouchOtherSessions() throws Exception {
        append("one", "first", "answer"); append("two", "other", "answer");
        byte[] other = Files.readAllBytes(file("two"));
        Path obstruction = file("one").resolveSibling("one.json.tmp");
        Files.createDirectory(obstruction);
        try { append("one", "unsaved", "answer"); fail(); } catch (IllegalStateException expected) { }
        assertEquals(2, WebAiSessionStore.size("one"));
        assertArrayEquals(other, Files.readAllBytes(file("two")));
        Files.delete(obstruction); WebAiSessionStore.checkpoint("one");
        WebAiSessionStore.useDirectory(root); assertEquals(2, WebAiSessionStore.size("one"));
    }

    @Test public void busySessionsRejectDuplicateRequests() {
        assertTrue(WebAiSessionStore.acquire("one"));
        assertFalse(WebAiSessionStore.acquire("one"));
        assertEquals(false, WebCli.exec("/reset", "", "one").get("ok"));
        WebAiSessionStore.release("one");
        assertTrue(WebAiSessionStore.acquire("one")); WebAiSessionStore.release("one");
    }

    @Test public void expiryRemovesPrimaryBackupAndDoesNotReturnAfterRestart() throws Exception {
        append("expired", "old", "answer"); append("expired", "old again", "answer");
        JsonObject snapshot = JsonParser.parseString(new String(Files.readAllBytes(file("expired")), StandardCharsets.UTF_8)).getAsJsonObject();
        snapshot.addProperty("lastConversation", System.currentTimeMillis() - 31L * 24 * 60 * 60 * 1000);
        Files.write(file("expired"), snapshot.toString().getBytes(StandardCharsets.UTF_8));
        WebAiSessionStore.useDirectory(root);
        assertTrue(WebAiSessionStore.listSessions().isEmpty());
        assertFalse(Files.exists(file("expired"))); assertFalse(Files.exists(AtomicJsonFile.backup(file("expired"))));
        WebAiSessionStore.useDirectory(root); assertEquals(0, WebAiSessionStore.size("expired"));
    }

    @Test public void resetBackupCannotRestoreClearedMemory() throws Exception {
        append("one", "old", "answer"); WebAiSessionStore.reset("one");
        Files.write(file("one"), "corrupt".getBytes(StandardCharsets.UTF_8));
        WebAiSessionStore.useDirectory(root); assertEquals(0, WebAiSessionStore.size("one"));
        assertEquals(0, WebAiSessionStore.execution("one").messages.size());
    }

    @Test public void migrationIsAValidSessionName() {
        append("migration", "request", "answer");
        WebAiSessionStore.useDirectory(root); assertEquals(1, WebAiSessionStore.size("migration"));
    }

    @Test public void finalResponseCheckpointRecoversEvenBeforeTranscriptWasUpdated() {
        append("one", "task", "任务执行中，进度将逐步保存。");
        ToolConversation task = WebAiSessionStore.execution("one");
        task.turnId = 1; task.status = "RUNNING";
        task.addText("user", "task"); task.addText("assistant", "completed answer");
        WebAiSessionStore.checkpoint("one"); WebAiSessionStore.useDirectory(root);
        assertEquals("COMPLETED", WebAiSessionStore.execution("one").status);
        List<?> messages = (List<?>) WebAiSessionStore.transcript("one").get("messages");
        assertEquals("completed answer", ((Map<?, ?>) messages.get(1)).get("content"));
    }
}
