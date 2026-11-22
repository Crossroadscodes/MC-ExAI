package com.exai.web;

import com.exai.web.tool.ToolContext;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpPrincipal;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/** Exercises the real handlers and authentication wrapper with an in-memory HTTP exchange. */
public class WebServerToolApiTest {
    private static String token;
    private static final String[] PATHS = {
            "/api/tools", "/api/tools/execute", "/api/tools/confirm", "/api/tools/cancel"
    };
    private static final HttpHandler[] HANDLERS = {
            WebServer::handleTools, WebServer::handleToolExecute,
            WebServer::handleToolConfirm, WebServer::handleToolCancel
    };

    @BeforeClass
    public static void login() throws Exception {
        token = WebAuth.login("test-admin", "test-only-password", "test-admin",
                WebAuth.hashPassword("test-only-password"), null);
        assertNotNull(token);
    }

    @AfterClass
    public static void logout() {
        WebAuth.logout(token);
    }

    @Test
    public void everyEndpointRequiresAuthenticationBeforeReadingBody() throws Exception {
        for (int i = 0; i < PATHS.length; i++) {
            Exchange exchange = new Exchange(i == 0 ? "GET" : "POST", PATHS[i], "not-json", false);
            WebServer.protectedHandler(HANDLERS[i]).handle(exchange);
            assertResponse(exchange, 401);
            assertFalse(exchange.bodyRead);
        }
    }

    @Test
    public void mutationsRetainOriginCheck() throws Exception {
        for (int i = 1; i < PATHS.length; i++) {
            Exchange exchange = new Exchange("POST", PATHS[i], "{}", true);
            exchange.requestHeaders.set("Origin", "https://another-host.example");
            WebServer.protectedHandler(HANDLERS[i]).handle(exchange);
            assertResponse(exchange, 403);
            assertFalse(exchange.bodyRead);
        }
    }

    @Test
    public void discoveryUsesRegistryAndReturnsDefensiveResponses() throws Exception {
        Exchange exchange = call(0, "GET", "");
        JsonObject result = assertResponse(exchange, 200);
        assertTrue(result.get("ok").getAsBoolean());
        assertEquals(PluginConfigTool.toolSchemas(), result.getAsJsonArray("tools"));
        assertEquals(PluginConfigTool.toolNames().size(), result.getAsJsonArray("tools").size());
        result.getAsJsonArray("tools").remove(0);
        assertEquals(12, assertResponse(call(0, "GET", ""), 200).getAsJsonArray("tools").size());
    }

    @Test
    public void wrongMethodsAndPrefixRoutesAreRejected() throws Exception {
        for (int i = 0; i < PATHS.length; i++) {
            Exchange method = call(i, i == 0 ? "POST" : "GET", "{}");
            assertResponse(method, 405);
            assertEquals(i == 0 ? "GET" : "POST", method.responseHeaders.getFirst("Allow"));
            Exchange suffix = new Exchange(i == 0 ? "GET" : "POST", PATHS[i] + "/extra", "{}", true);
            WebServer.protectedHandler(HANDLERS[i]).handle(suffix);
            assertResponse(suffix, 404);
        }
    }

    @Test
    public void malformedJsonAndInvalidEnvelopesReturn400() throws Exception {
        for (String body : new String[]{"", "null", "[]", "42", "{", "{} {}",
                "{name:'x'}", "{\"name\":\"x\",}", "/*comment*/ {}", "{\"name\":NaN}"}) {
            assertResponse(call(1, "POST", body), 400);
        }
    }

    @Test
    public void executionValidatesFieldTypesWithoutEchoingInput() throws Exception {
        for (String body : new String[]{"{}", "{\"name\":null}", "{\"name\":42}",
                "{\"name\":true}", "{\"name\":{}}", "{\"name\":\"  \"}",
                "{\"name\":\"x\",\"arguments\":null}", "{\"name\":\"x\",\"arguments\":[]}",
                "{\"name\":\"x\",\"arguments\":\"private-value\"}",
                "{\"name\":\"x\",\"context\":null}", "{\"name\":\"x\",\"context\":[]}",
                "{\"name\":\"x\",\"context\":{\"cwd\":null}}",
                "{\"name\":\"x\",\"context\":{\"cwd\":7}}",
                "{\"name\":\"x\",\"context\":{\"cwd\":{}}}"}) {
            JsonObject result = assertResponse(call(1, "POST", body), 400);
            assertFalse(result.toString().contains("private-value"));
        }
    }

    @Test
    public void unknownToolAndMissingToolArgumentsAreBusinessFailures() throws Exception {
        JsonObject unknown = assertResponse(call(1, "POST", "{\"name\":\"does_not_exist\",\"arguments\":{}}"), 200);
        assertEquals("TOOL_NOT_FOUND", unknown.get("error_code").getAsString());
        assertFalse(unknown.has("result"));
        JsonObject missing = assertResponse(call(1, "POST", "{\"name\":\"get_yaml_value\"}"), 200);
        assertEquals("INVALID_ARGUMENT", missing.get("error_code").getAsString());
        assertFalse(missing.get("retryable").getAsBoolean());
        JsonObject explicit = assertResponse(call(1, "POST", "{\"name\":\"get_yaml_value\",\"arguments\":{}}"), 200);
        assertEquals(explicit, missing);
    }

    @Test
    public void httpContextCannotForgeCliRollbackRelationships() {
        ToolContext defaults = WebServer.externalToolContext(new JsonObject());
        assertEquals("", defaults.getCwd());
        JsonObject request = JsonParser.parseString("{\"sessionId\":\"forged\",\"turnId\":99,"
                + "\"context\":{\"cwd\":\"Example\",\"sessionId\":\"forged\",\"turnId\":99}}")
                .getAsJsonObject();
        ToolContext context = WebServer.externalToolContext(request);
        assertEquals("Example", context.getCwd());
        assertEquals("", context.getSessionId());
        assertEquals(0L, context.getTurnId());
    }

    @Test
    public void confirmationValidationAndResultsAreStructured() throws Exception {
        for (int endpoint : new int[]{2, 3}) {
            for (String body : new String[]{"{}", "{\"confirmation_id\":null}",
                    "{\"confirmation_id\":42}", "{\"confirmation_id\":{}}", "{\"confirmation_id\":\" \"}"}) {
                assertResponse(call(endpoint, "POST", body), 400);
            }
            JsonObject result = assertResponse(call(endpoint, "POST", "{\"confirmation_id\":\"missing-test-id\"}"), 200);
            assertEquals("NOT_FOUND", result.get("error_code").getAsString());
            assertEquals("missing-test-id", result.get("confirmation_id").getAsString());
            JsonObject expected = endpoint == 2 ? PluginConfigTool.confirmPendingResult("missing-test-id")
                    : PluginConfigTool.cancelPendingResult("missing-test-id");
            assertEquals(expected, result);
        }
    }

    @Test
    public void matchingOriginAndQueryStringAreAccepted() throws Exception {
        Exchange exchange = new Exchange("POST", PATHS[1] + "?client=test", "{\"name\":\"unknown\"}", true);
        exchange.requestHeaders.set("Origin", "https://127.0.0.1:8080");
        WebServer.protectedHandler(HANDLERS[1]).handle(exchange);
        assertResponse(exchange, 200);
    }

    private static Exchange call(int endpoint, String method, String body) throws Exception {
        Exchange exchange = new Exchange(method, PATHS[endpoint], body, true);
        WebServer.protectedHandler(HANDLERS[endpoint]).handle(exchange);
        return exchange;
    }

    private static JsonObject assertResponse(Exchange exchange, int status) {
        assertEquals(exchange.output.toString(), status, exchange.status);
        assertTrue(exchange.closed);
        assertEquals("application/json; charset=utf-8", exchange.responseHeaders.getFirst("Content-Type"));
        assertEquals("no-store", exchange.responseHeaders.getFirst("Cache-Control"));
        JsonObject result = JsonParser.parseString(new String(exchange.output.toByteArray(), StandardCharsets.UTF_8))
                .getAsJsonObject();
        if (status >= 400) {
            assertFalse(result.get("ok").getAsBoolean());
            assertTrue(result.has("error"));
        }
        return result;
    }

    private static final class Exchange extends HttpExchange {
        final Headers requestHeaders = new Headers();
        final Headers responseHeaders = new Headers();
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final String method;
        final URI uri;
        final InputStream input;
        int status;
        boolean closed;
        boolean bodyRead;

        Exchange(String method, String path, String body, boolean authenticated) {
            this.method = method;
            this.uri = URI.create(path);
            this.input = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
            requestHeaders.set("Host", "127.0.0.1:8080");
            if (authenticated) requestHeaders.set("Cookie", "EXAI_SESSION=" + token);
        }

        @Override public Headers getRequestHeaders() { return requestHeaders; }
        @Override public Headers getResponseHeaders() { return responseHeaders; }
        @Override public URI getRequestURI() { return uri; }
        @Override public String getRequestMethod() { return method; }
        @Override public HttpContext getHttpContext() { return null; }
        @Override public void close() { closed = true; }
        @Override public InputStream getRequestBody() { bodyRead = true; return input; }
        @Override public OutputStream getResponseBody() { return output; }
        @Override public void sendResponseHeaders(int code, long length) { status = code; }
        @Override public InetSocketAddress getRemoteAddress() { return new InetSocketAddress("127.0.0.1", 12345); }
        @Override public InetSocketAddress getLocalAddress() { return new InetSocketAddress("127.0.0.1", 8080); }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public Object getAttribute(String name) { return null; }
        @Override public void setAttribute(String name, Object value) { }
        @Override public void setStreams(InputStream input, OutputStream output) { }
        @Override public HttpPrincipal getPrincipal() { return null; }
        public int getResponseCode() { return status; }
    }
}
