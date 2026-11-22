package com.exai.service;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class ToolStreamRecoveryTest {
    @Test public void eofAndLengthLimitNeverExecutePartialCalls() throws Exception {
        for (String ending : new String[]{"", "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}\n\ndata: [DONE]\n\n"}) {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/chat/completions", exchange -> {
                String delta = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\","
                        + "\"function\":{\"name\":\"propose_yaml_set\",\"arguments\":\"{}\"}}]}}]}\n\n";
                byte[] response = (delta + ending).getBytes(StandardCharsets.UTF_8);
                exchange.getRequestBody().close();
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            server.start();
            try {
                LLMService model = new LLMService("test", "http://127.0.0.1:" + server.getAddress().getPort(), "test", 0);
                ToolConversation state = new ToolConversation(); state.addText("user", "task");
                AtomicInteger executed = new AtomicInteger();
                LLMService.ToolStreamListener listener = new LLMService.ToolStreamListener() {
                    public void onContentDelta(String text) { }
                    public void onStatus(String text) { }
                    public void onToolCallStarted(String name) { }
                    public void onToolCallFinished(String name, JsonObject result) { }
                };
                LLMService.ToolChatResult result = model.runWithTools("sys", state, new JsonArray(),
                        (name, args) -> { executed.incrementAndGet(); return new JsonObject(); },
                        listener, 0, 1000, 20, 24000, 8, 4000, () -> {});
                assertFalse(result.isOk()); assertEquals(0, executed.get());
                assertEquals(1, state.messages.size()); assertTrue(state.canContinue());
            } finally { server.stop(0); }
        }
    }
}
