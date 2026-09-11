package com.exai.service;

import com.exai.utils.HttpJsonClient;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public class LLMService {
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final double answerTemperature;

    public LLMService(String apiKey, String baseUrl, String model, double answerTemperature) {
        this.apiKey = apiKey;
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.model = model;
        this.answerTemperature = answerTemperature;
    }

    /** 本地工具执行器：收到模型返回的 function name 与 arguments，返回工具结果 JSON。 */
    public interface ToolExecutor {
        JsonObject execute(String name, JsonObject arguments);
    }

    /** 流式工具调用监听器。 */
    public interface ToolStreamListener {
        void onContentDelta(String text) throws IOException;
        void onStatus(String message) throws IOException;
        void onToolCallStarted(String name) throws IOException;
        void onToolCallFinished(String name, JsonObject result) throws IOException;
    }

    /** 带工具调用的对话结果。 */
    public static final class ToolChatResult {
        private final boolean ok;
        private final String content;
        private final String error;
        private final int iterations;

        public ToolChatResult(boolean ok, String content, String error, int iterations) {
            this.ok = ok;
            this.content = content;
            this.error = error;
            this.iterations = iterations;
        }

        public boolean isOk() { return ok; }
        public String getContent() { return content; }
        public String getError() { return error; }
        public int getIterations() { return iterations; }
    }

    public String generateResponse(String prompt) {
        try {
            String content = request(prompt, answerTemperature, 500);
            return content != null ? content : com.exai.i18n.Lang.get("service.llm.empty");
        } catch (Exception e) {
            System.err.println("LLM call error: " + e.getMessage());
            return com.exai.i18n.Lang.get("service.llm.error");
        }
    }

    /**
     * 通用补全调用，可自定义温度与最大 token。失败或无内容时返回 null（不返回提示文案），
     * 便于调用方（如知识初审）自行判断与降级。
     */
    public String complete(String prompt, double temperature, int maxTokens) {
        try {
            return request(prompt, temperature, maxTokens);
        } catch (Exception e) {
            System.err.println("LLM call error: " + e.getMessage());
            return null;
        }
    }

    /**
     * OpenAI-compatible function calling：发送 tools，执行 message.tool_calls，并把 tool 结果回传，
     * 直到模型返回最终文本或达到最大轮次。失败时返回结构化错误，不使用聊天兜底文案。
     */
    public ToolChatResult completeWithTools(String systemPrompt,
                                            String userPrompt,
                                            JsonArray tools,
                                            ToolExecutor executor,
                                            double temperature,
                                            int maxTokens,
                                            int maxIterations) {
        return completeWithTools(systemPrompt, new JsonArray(), userPrompt, tools, executor,
                temperature, maxTokens, maxIterations);
    }

    public ToolChatResult completeWithTools(String systemPrompt,
                                            JsonArray historyMessages,
                                            String userPrompt,
                                            JsonArray tools,
                                            ToolExecutor executor,
                                            double temperature,
                                            int maxTokens,
                                            int maxIterations) {
        JsonArray messages = new JsonArray();
        JsonObject sys = new JsonObject();
        sys.addProperty("role", "system");
        sys.addProperty("content", systemPrompt == null ? "" : systemPrompt);
        messages.add(sys);
        appendSafeHistory(messages, historyMessages);
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", userPrompt == null ? "" : userPrompt);
        messages.add(user);

        int limit = Math.max(1, maxIterations);
        for (int i = 1; i <= limit; i++) {
            try {
                JsonObject body = new JsonObject();
                body.addProperty("model", model);
                body.addProperty("temperature", temperature);
                body.addProperty("max_tokens", maxTokens);
                body.add("messages", messages);
                if (tools != null && tools.size() > 0) {
                    body.add("tools", tools);
                    body.addProperty("tool_choice", "auto");
                }

                JsonObject resp = HttpJsonClient.postJson(baseUrl + "/chat/completions", apiKey, body);
                JsonObject msg = firstMessage(resp);
                if (msg == null) {
                    return new ToolChatResult(false, null, "模型响应中没有 message", i);
                }
                if (!msg.has("role")) {
                    msg.addProperty("role", "assistant");
                }
                messages.add(msg.deepCopy());

                JsonArray toolCalls = getToolCalls(msg);
                if (toolCalls != null && toolCalls.size() > 0) {
                    for (JsonElement el : toolCalls) {
                        JsonObject toolMsg = runToolCall(executor, el);
                        messages.add(toolMsg);
                    }
                    continue;
                }

                String content = contentOf(msg);
                if (content == null || content.trim().isEmpty()) {
                    return new ToolChatResult(false, null, "模型未返回内容", i);
                }
                return new ToolChatResult(true, content.trim(), null, i);
            } catch (Exception e) {
                System.err.println("LLM tool call error: " + e.getMessage());
                return new ToolChatResult(false, null, e.getMessage(), i);
            }
        }
        return new ToolChatResult(false, null, "工具调用轮次过多，已停止", limit);
    }

    public ToolChatResult completeWithToolsStreaming(String systemPrompt,
                                                     JsonArray historyMessages,
                                                     String userPrompt,
                                                     JsonArray tools,
                                                     ToolExecutor executor,
                                                     ToolStreamListener listener,
                                                     double temperature,
                                                     int maxTokens,
                                                     int maxIterations) {
        JsonArray messages = new JsonArray();
        JsonObject sys = new JsonObject();
        sys.addProperty("role", "system");
        sys.addProperty("content", systemPrompt == null ? "" : systemPrompt);
        messages.add(sys);
        appendSafeHistory(messages, historyMessages);
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", userPrompt == null ? "" : userPrompt);
        messages.add(user);

        int limit = Math.max(1, maxIterations);
        for (int i = 1; i <= limit; i++) {
            try {
                JsonObject body = new JsonObject();
                body.addProperty("model", model);
                body.addProperty("temperature", temperature);
                body.addProperty("max_tokens", maxTokens);
                body.addProperty("stream", true);
                body.add("messages", messages);
                if (tools != null && tools.size() > 0) {
                    body.add("tools", tools);
                    body.addProperty("tool_choice", "auto");
                }

                StreamAccumulator acc = new StreamAccumulator(listener);
                HttpJsonClient.postJsonStream(baseUrl + "/chat/completions", apiKey, body, acc);
                JsonObject msg = acc.toAssistantMessage();
                messages.add(msg.deepCopy());

                JsonArray toolCalls = getToolCalls(msg);
                if (toolCalls != null && toolCalls.size() > 0) {
                    for (JsonElement el : toolCalls) {
                        String name = toolName(el);
                        if (listener != null) {
                            listener.onToolCallStarted(name == null ? "unknown" : name);
                        }
                        JsonObject toolMsg = runToolCall(executor, el);
                        if (listener != null) {
                            JsonObject result = parseToolResult(toolMsg);
                            listener.onToolCallFinished(name == null ? "unknown" : name, result);
                        }
                        messages.add(toolMsg);
                    }
                    continue;
                }

                String content = contentOf(msg);
                if (content == null || content.trim().isEmpty()) {
                    return new ToolChatResult(false, null, "模型未返回内容", i);
                }
                return new ToolChatResult(true, content.trim(), null, i);
            } catch (Exception e) {
                System.err.println("LLM streaming tool call error: " + e.getMessage());
                return new ToolChatResult(false, null, e.getMessage(), i);
            }
        }
        return new ToolChatResult(false, null, "工具调用轮次过多，已停止", limit);
    }

    private static void appendSafeHistory(JsonArray messages, JsonArray history) {
        if (history == null) {
            return;
        }
        for (JsonElement el : history) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject m = el.getAsJsonObject();
            String role = str(m, "role");
            if (!"user".equals(role) && !"assistant".equals(role)) {
                continue;
            }
            String content = contentOf(m);
            if (content == null) {
                continue;
            }
            JsonObject copy = new JsonObject();
            copy.addProperty("role", role);
            copy.addProperty("content", content);
            messages.add(copy);
        }
    }

    /**
     * 多模态视觉补全：把一段文字提示与一张图片一起送给视觉模型（如 qwen-vl-*）。
     * 本实例的 {@code model} 必须是支持视觉的模型。失败或无内容时返回 null。
     *
     * @param imageDataUrl 形如 {@code data:image/png;base64,xxxx} 的图片数据 URL
     */
    public String completeVision(String prompt, String imageDataUrl, double temperature, int maxTokens) {
        try {
            return requestVision(prompt, imageDataUrl, temperature, maxTokens);
        } catch (Exception e) {
            System.err.println("LLM vision call error: " + e.getMessage());
            return null;
        }
    }

    private JsonObject runToolCall(ToolExecutor executor, JsonElement el) {
        String id = "";
        JsonObject result;
        try {
            JsonObject call = el.getAsJsonObject();
            id = str(call, "id");
            JsonObject fn = call.has("function") && call.get("function").isJsonObject()
                    ? call.getAsJsonObject("function") : null;
            if (fn == null) {
                result = errorJson("tool_call 缺少 function");
            } else {
                String name = str(fn, "name");
                String argText = str(fn, "arguments");
                JsonObject args;
                try {
                    args = (argText == null || argText.trim().isEmpty())
                            ? new JsonObject()
                            : JsonParser.parseString(argText).getAsJsonObject();
                } catch (Exception pe) {
                    args = new JsonObject();
                    result = errorJson("工具参数不是合法 JSON: " + pe.getMessage());
                    return toolMessage(id, result);
                }
                if (executor == null) {
                    result = errorJson("未配置本地工具执行器");
                } else {
                    try {
                        result = executor.execute(name, args);
                        if (result == null) {
                            result = errorJson("工具返回 null");
                        }
                    } catch (Exception ex) {
                        result = errorJson("工具执行失败: " + ex.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            result = errorJson("解析 tool_call 失败: " + e.getMessage());
        }
        return toolMessage(id, result);
    }

    private static JsonObject toolMessage(String id, JsonObject result) {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "tool");
        msg.addProperty("tool_call_id", id == null ? "" : id);
        msg.addProperty("content", result == null ? "{}" : result.toString());
        return msg;
    }

    private static JsonObject parseToolResult(JsonObject toolMsg) {
        try {
            String content = contentOf(toolMsg);
            if (content != null && !content.trim().isEmpty()) {
                return JsonParser.parseString(content).getAsJsonObject();
            }
        } catch (Exception ignored) {
        }
        return new JsonObject();
    }

    private static String toolName(JsonElement el) {
        try {
            JsonObject call = el.getAsJsonObject();
            JsonObject fn = call.has("function") && call.get("function").isJsonObject()
                    ? call.getAsJsonObject("function") : null;
            return fn == null ? null : str(fn, "name");
        } catch (Exception ignored) {
            return null;
        }
    }

    private static JsonObject errorJson(String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("error", msg == null ? "unknown" : msg);
        return o;
    }

    private static String str(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        return obj.get(key).getAsString();
    }

    private String requestVision(String prompt, String imageDataUrl, double temperature, int maxTokens) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("temperature", temperature);
        body.addProperty("max_tokens", maxTokens);

        JsonArray content = new JsonArray();
        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", prompt);
        content.add(textPart);
        JsonObject imagePart = new JsonObject();
        imagePart.addProperty("type", "image_url");
        JsonObject imageUrl = new JsonObject();
        imageUrl.addProperty("url", imageDataUrl);
        imagePart.add("image_url", imageUrl);
        content.add(imagePart);

        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.add("content", content);
        JsonArray messages = new JsonArray();
        messages.add(userMsg);
        body.add("messages", messages);

        return parseContent(HttpJsonClient.postJson(baseUrl + "/chat/completions", apiKey, body));
    }

    private String request(String prompt, double temperature, int maxTokens) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("temperature", temperature);
        body.addProperty("max_tokens", maxTokens);

        JsonArray messages = new JsonArray();
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", prompt);
        messages.add(userMsg);
        body.add("messages", messages);

        return parseContent(HttpJsonClient.postJson(
                baseUrl + "/chat/completions", apiKey, body));
    }

    private static String parseContent(JsonObject resp) {
        JsonObject msg = firstMessage(resp);
        return msg == null ? null : contentOf(msg);
    }

    private static final class StreamAccumulator implements HttpJsonClient.StreamHandler {
        private final ToolStreamListener listener;
        private final StringBuilder content = new StringBuilder();
        private final Map<Integer, ToolCallPart> toolCalls = new LinkedHashMap<>();

        StreamAccumulator(ToolStreamListener listener) {
            this.listener = listener;
        }

        @Override
        public void onData(JsonObject data) throws IOException {
            if (data == null || !data.has("choices")) {
                return;
            }
            JsonArray choices = data.getAsJsonArray("choices");
            if (choices.size() == 0 || !choices.get(0).isJsonObject()) {
                return;
            }
            JsonObject choice = choices.get(0).getAsJsonObject();
            if (!choice.has("delta") || !choice.get("delta").isJsonObject()) {
                return;
            }
            JsonObject delta = choice.getAsJsonObject("delta");
            String text = str(delta, "content");
            if (text != null && !text.isEmpty()) {
                content.append(text);
                if (listener != null) {
                    listener.onContentDelta(text);
                }
            }
            if (delta.has("tool_calls") && delta.get("tool_calls").isJsonArray()) {
                JsonArray arr = delta.getAsJsonArray("tool_calls");
                for (JsonElement el : arr) {
                    if (el.isJsonObject()) {
                        addToolDelta(el.getAsJsonObject());
                    }
                }
            }
        }

        @Override
        public void onDone() {
        }

        JsonObject toAssistantMessage() {
            JsonObject msg = new JsonObject();
            msg.addProperty("role", "assistant");
            msg.addProperty("content", content.toString());
            if (!toolCalls.isEmpty()) {
                JsonArray arr = new JsonArray();
                for (ToolCallPart part : toolCalls.values()) {
                    arr.add(part.toJson());
                }
                msg.add("tool_calls", arr);
            }
            return msg;
        }

        private void addToolDelta(JsonObject delta) {
            int index = delta.has("index") && !delta.get("index").isJsonNull()
                    ? delta.get("index").getAsInt() : toolCalls.size();
            ToolCallPart part = toolCalls.get(index);
            if (part == null) {
                part = new ToolCallPart(index);
                toolCalls.put(index, part);
            }
            String id = str(delta, "id");
            if (id != null && !id.isEmpty()) {
                part.id = id;
            }
            String type = str(delta, "type");
            if (type != null && !type.isEmpty()) {
                part.type = type;
            }
            if (delta.has("function") && delta.get("function").isJsonObject()) {
                JsonObject fn = delta.getAsJsonObject("function");
                String name = str(fn, "name");
                if (name != null && !name.isEmpty()) {
                    part.name = name;
                }
                String args = str(fn, "arguments");
                if (args != null) {
                    part.arguments.append(args);
                }
            }
        }
    }

    private static final class ToolCallPart {
        final int index;
        String id;
        String type = "function";
        String name;
        final StringBuilder arguments = new StringBuilder();

        ToolCallPart(int index) {
            this.index = index;
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("id", id == null || id.isEmpty() ? "call_" + index : id);
            o.addProperty("type", type == null || type.isEmpty() ? "function" : type);
            JsonObject fn = new JsonObject();
            fn.addProperty("name", name == null ? "" : name);
            fn.addProperty("arguments", arguments.toString());
            o.add("function", fn);
            return o;
        }
    }

    private static JsonObject firstMessage(JsonObject resp) {
        if (resp != null && resp.has("choices")) {
            JsonArray choices = resp.getAsJsonArray("choices");
            if (choices.size() > 0) {
                JsonObject first = choices.get(0).getAsJsonObject();
                if (first.has("message") && first.get("message").isJsonObject()) {
                    return first.getAsJsonObject("message");
                }
            }
        }
        return null;
    }

    private static JsonArray getToolCalls(JsonObject msg) {
        return msg != null && msg.has("tool_calls") && msg.get("tool_calls").isJsonArray()
                ? msg.getAsJsonArray("tool_calls") : null;
    }

    private static String contentOf(JsonObject msg) {
        if (msg != null && msg.has("content") && !msg.get("content").isJsonNull()) {
            return msg.get("content").getAsString();
        }
        return null;
    }

    private static String stripTrailingSlash(String s) {
        if (s == null) return "";
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
