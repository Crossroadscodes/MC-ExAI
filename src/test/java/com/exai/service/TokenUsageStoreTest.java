package com.exai.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.*;

public class TokenUsageStoreTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private File root;

    @Before public void setup() {
        root = temp.getRoot();
        TokenUsageStore.useFileForTest(new File(root, "data/token-usage.json"));
    }

    @After public void teardown() { TokenUsageStore.useFileForTest(null); }

    private List<String> events() throws IOException {
        File[] logs = new File(root, "logs").listFiles();
        assertNotNull(logs);
        assertEquals(1, logs.length);
        return Files.readAllLines(logs[0].toPath(), StandardCharsets.UTF_8);
    }

    @Test public void missingUsageAndMissingTotalHaveSeparateReasons() throws Exception {
        TokenUsageStore.record("test", null, true, 125, new JsonObject(), null);
        JsonObject usage = new JsonObject(); usage.addProperty("prompt_tokens", 12);
        TokenUsageStore.record("test", usage, false, 10, new JsonObject(), null);
        List<String> lines = events();
        JsonObject missing = JsonParser.parseString(lines.get(0)).getAsJsonObject();
        assertEquals("missing_usage", missing.get("reason").getAsString());
        assertTrue(missing.get("streaming").getAsBoolean());
        assertEquals(125, missing.get("durationMs").getAsLong());
        JsonObject partial = JsonParser.parseString(lines.get(1)).getAsJsonObject();
        assertEquals("missing_total_tokens", partial.get("reason").getAsString());
        assertEquals(12, partial.getAsJsonObject("reportedTokens").get("prompt_tokens").getAsInt());
        assertEquals(2L, TokenUsageStore.recent(1).get(0).get("unknownCalls"));
    }

    @Test public void invalidTotalsAreUnknownInsteadOfBeingCountedAsZero() throws Exception {
        for (String value : new String[]{"null", "\"secret\"", "-1", "1.5"}) {
            JsonObject usage = new JsonObject(); usage.add("total_tokens", JsonParser.parseString(value));
            TokenUsageStore.record("test", usage);
        }
        assertEquals(4L, TokenUsageStore.recent(1).get(0).get("unknownCalls"));
        for (String line : events()) {
            assertEquals("invalid_total_tokens", JsonParser.parseString(line).getAsJsonObject().get("reason").getAsString());
            assertFalse(line.contains("secret"));
        }
    }

    @Test public void validUsageDoesNotWriteDiagnosticLogs() {
        JsonObject usage = new JsonObject();
        usage.addProperty("prompt_tokens", 12); usage.addProperty("completion_tokens", 8);
        usage.addProperty("total_tokens", 20);
        TokenUsageStore.record("test", usage);
        assertEquals(0L, TokenUsageStore.recent(1).get(0).get("unknownCalls"));
        assertEquals(20L, TokenUsageStore.recent(1).get(0).get("totalTokens"));
        assertFalse(new File(root, "logs").exists());
    }

    @Test public void httpFailureRecordsStatusWithoutResponseBodyOrCredentials() throws Exception {
        TokenUsageStore.record("test", null, false, 30, null,
                new IOException("HTTP 401: secret-key private conversation"));
        String line = events().get(0);
        JsonObject event = JsonParser.parseString(line).getAsJsonObject();
        assertEquals(401, event.get("httpStatus").getAsInt());
        assertEquals("http_error", event.get("failureKind").getAsString());
        assertFalse(line.contains("secret-key"));
        assertFalse(line.contains("private conversation"));
    }

    @Test public void diagnosticWriteFailureDoesNotBreakAccounting() throws Exception {
        Files.write(new File(root, "logs").toPath(), new byte[]{1});
        TokenUsageStore.record("test", null);
        assertEquals(1L, TokenUsageStore.recent(1).get(0).get("unknownCalls"));
        assertTrue(new File(root, "data/token-usage.json").isFile());
    }

    @Test public void nonStreamingResponseLogsMissingUsageWithoutContent() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            byte[] response = "{\"choices\":[{\"message\":{\"content\":\"private answer\"}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getRequestBody().close();
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            LLMService service = new LLMService("secret-key", "http://127.0.0.1:" + server.getAddress().getPort(), "test", 0);
            service.requestToolMessage(new JsonObject(), null);
            String line = events().get(0);
            JsonObject event = JsonParser.parseString(line).getAsJsonObject();
            assertEquals("missing_usage", event.get("reason").getAsString());
            assertFalse(event.get("streaming").getAsBoolean());
            assertTrue(event.get("responseObserved").getAsBoolean());
            assertFalse(line.contains("private answer"));
            assertFalse(line.contains("secret-key"));
        } finally { server.stop(0); }
    }

    @Test public void streamingEofRecordsObservedResponseAndInterruptionOnce() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            byte[] response = "data: {\"choices\":[],\"usage\":null}\n\n".getBytes(StandardCharsets.UTF_8);
            exchange.getRequestBody().close();
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            LLMService service = new LLMService("secret-key", "http://127.0.0.1:" + server.getAddress().getPort(), "test", 0);
            LLMService.ToolStreamListener listener = new LLMService.ToolStreamListener() {
                public void onContentDelta(String text) { }
                public void onStatus(String text) { }
                public void onToolCallStarted(String name) { }
                public void onToolCallFinished(String name, JsonObject result) { }
            };
            try {
                service.requestToolMessage(new JsonObject(), listener);
                fail("Expected interrupted stream");
            } catch (IOException expected) { }
            List<String> lines = events();
            assertEquals(1, lines.size());
            JsonObject event = JsonParser.parseString(lines.get(0)).getAsJsonObject();
            assertEquals("stream_interrupted", event.get("failureKind").getAsString());
            assertEquals("null", event.get("usageType").getAsString());
            assertTrue(event.get("responseObserved").getAsBoolean());
            assertEquals(1L, TokenUsageStore.recent(1).get(0).get("calls"));
        } finally { server.stop(0); }
    }
}
