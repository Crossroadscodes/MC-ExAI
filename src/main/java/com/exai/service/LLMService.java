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

    /** Returns whether all fields required for an OpenAI-compatible request are configured. */
    public boolean isConfigured() {
        if (!hasText(apiKey) || !hasText(baseUrl) || !hasText(model)) {
            return false;
        }
        String key = apiKey.trim().toLowerCase(java.util.Locale.ROOT);
        return !key.contains("your-api")
                && !key.contains("your_api")
                && !key.contains("填你自己的");
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    /** 本地工具执行器：收到模型返回的 function name 与 arguments，返回工具结果 JSON。 */
    public interface ToolExecutor {
        JsonObject execute(String name, JsonObject arguments);
        default JsonObject recover(String name, JsonObject arguments) { return null; }
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
        private String status;

        public ToolChatResult(boolean ok, String content, String error, int iterations) {
            this.ok = ok;
            this.content = content;
            this.error = error;
            this.iterations = iterations;
            this.status = ok ? "COMPLETED" : "FAILED";
        }

        public static ToolChatResult paused(String reason, int iterations) {
            ToolChatResult result = new ToolChatResult(true, reason, null, iterations);
            result.status = "PAUSED";
            return result;
        }
        public String getStatus() { return status; }
        public boolean isOk() { return ok; }
        public String getContent() { return content; }
        public String getError() { return error; }
        public int getIterations() { return iterations; }
    }

    public String generateResponse(String prompt) {
        if (!isConfigured()) {
            return com.exai.i18n.Lang.get("service.llm.not-configured");
        }
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
        if (!isConfigured()) {
            return null;
        }
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
        ToolConversation state = new ToolConversation();
        appendSafeHistory(state.messages, historyMessages);
        state.addText("user", userPrompt == null ? "" : userPrompt);
        return runWithTools(systemPrompt, state, tools, executor, null, temperature, maxTokens,
                maxIterations, 24000, 8, 4000, () -> {});
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
        ToolConversation state = new ToolConversation();
        appendSafeHistory(state.messages, historyMessages);
        state.addText("user", userPrompt == null ? "" : userPrompt);
        return runWithTools(systemPrompt, state, tools, executor, listener, temperature, maxTokens,
                maxIterations, 24000, 8, 4000, () -> {});
    }

    public interface Checkpoint { void save(); }

    /** A completed tool response is persisted before advancing to the next tool/model request. */
    public ToolChatResult runWithTools(String systemPrompt, ToolConversation state, JsonArray tools,
                                      ToolExecutor executor, ToolStreamListener listener, double temperature,
                                      int maxTokens, int maxIterations, int contextChars, int recentGroups,
                                      int summaryChars, Checkpoint checkpoint) {
        int limit = Math.max(1, maxIterations);
        state.status = "RUNNING";
        state.reason = "";
        state.iterations = 0;
        try {
            checkpoint.save();
            finishPendingTools(state, executor, listener, checkpoint);
            if (!state.pendingInput.isEmpty()) {
                state.addText("user", state.pendingInput);
                state.pendingInput = "";
                checkpoint.save();
            }
            for (int i = 1; i <= limit; i++) {
                JsonArray messages = ConversationContext.prepare(state, systemPrompt, contextChars,
                        recentGroups, summaryChars, prompt -> complete(prompt, 0.0, Math.max(512, summaryChars)));
                state.iterations = i;
                state.totalIterations++;
                checkpoint.save();
                JsonObject body = new JsonObject();
                body.addProperty("model", model);
                body.addProperty("temperature", temperature);
                body.addProperty("max_tokens", maxTokens);
                body.add("messages", messages);
                if (tools != null && tools.size() > 0) {
                    body.add("tools", tools);
                    body.addProperty("tool_choice", "auto");
                }
                JsonObject msg = requestToolMessage(body, listener);
                if (msg == null) throw new IOException("模型响应中没有 message");
                if (!msg.has("role")) msg.addProperty("role", "assistant");
                JsonArray calls = getToolCalls(msg);
                if (calls != null) {
                    java.util.Set<String> ids = new java.util.HashSet<>();
                    for (JsonElement call : calls) {
                        String id = str(call.getAsJsonObject(), "id");
                        if (id == null || id.isEmpty() || !ids.add(id))
                            throw new IOException("工具调用 ID 缺失或重复，未执行此批调用");
                    }
                }
                String content = contentOf(msg);
                if ((calls == null || calls.size() == 0) && (content == null || content.trim().isEmpty()))
                    throw new IOException("模型未返回内容");
                state.add(msg);
                checkpoint.save();
                if (calls != null && calls.size() > 0) {
                    finishPendingTools(state, executor, listener, checkpoint);
                    continue;
                }
                state.status = "COMPLETED";
                checkpoint.save();
                return new ToolChatResult(true, content.trim(), null, i);
            }
            state.status = "PAUSED";
            state.reason = "已达到本次 " + limit + " 轮模型调用上限，进度已保存。点击继续或输入 /continue 可接着执行。";
            checkpoint.save();
            return ToolChatResult.paused(state.reason, state.iterations);
        } catch (Exception e) {
            state.status = "FAILED";
            state.reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            try { checkpoint.save(); } catch (Exception saveError) { state.reason = saveError.getMessage(); }
            return new ToolChatResult(false, null, state.reason, state.iterations);
        }
    }

    /** Transport seam used by deterministic tests. No partial streaming calls are executable. */
    protected JsonObject requestToolMessage(JsonObject body, ToolStreamListener listener) throws IOException {
        if (listener == null) {
            JsonObject response = postTracked(body);
            if (response != null && response.has("choices") && response.getAsJsonArray("choices").size() > 0) {
                String reason = str(response.getAsJsonArray("choices").get(0).getAsJsonObject(), "finish_reason");
                checkFinishReason(reason);
            }
            return firstMessage(response);
        }
        body.addProperty("stream", true);
        StreamAccumulator acc = new StreamAccumulator(listener);
        final JsonObject[] usage = new JsonObject[1];
        try {
            HttpJsonClient.postJsonStream(baseUrl + "/chat/completions", apiKey, body, acc,
                    data -> { if (data.has("usage") && data.get("usage").isJsonObject()) usage[0] = data.getAsJsonObject("usage"); });
            TokenUsageStore.record(model, usage[0]);
        } catch (IOException e) {
            TokenUsageStore.record(model, null);
            throw e;
        }
        if (!acc.done) throw new IOException("模型流式响应中断，未执行不完整的工具调用");
        checkFinishReason(acc.finishReason);
        return acc.toAssistantMessage();
    }

    private static void checkFinishReason(String reason) throws IOException {
        if ("length".equals(reason) || "content_filter".equals(reason))
            throw new IOException("模型响应未完整结束: " + reason + "；未执行此批工具调用");
    }

    private void finishPendingTools(ToolConversation state, ToolExecutor executor,
                                    ToolStreamListener listener, Checkpoint checkpoint) throws IOException {
        int assistantIndex = -1;
        for (int i = state.messages.size() - 1; i >= 0; i--) {
            JsonObject message = state.messages.get(i).getAsJsonObject();
            if ("assistant".equals(str(message, "role"))) { assistantIndex = i; break; }
        }
        if (assistantIndex < 0) return;
        JsonArray calls = getToolCalls(state.messages.get(assistantIndex).getAsJsonObject());
        if (calls == null) return;
        for (JsonElement call : calls) {
            String id = str(call.getAsJsonObject(), "id");
            if (id == null || id.isEmpty()) throw new IOException("工具调用缺少 id，无法可靠恢复");
            boolean completed = false;
            for (int j = assistantIndex + 1; j < state.messages.size(); j++) {
                JsonObject message = state.messages.get(j).getAsJsonObject();
                if ("tool".equals(str(message, "role")) && id.equals(str(message, "tool_call_id"))) completed = true;
            }
            if (completed) continue;
            String name = toolName(call);
            boolean uncertain = state.startedCalls.contains(id);
            if (!uncertain) state.startedCalls.add(id);
            checkpoint.save();
            if (listener != null) listener.onToolCallStarted(name == null ? "unknown" : name);
            JsonObject result;
            if (uncertain && !isReadOnlyTool(name)) {
                JsonObject recovered = null;
                try {
                    JsonObject fn = call.getAsJsonObject().getAsJsonObject("function");
                    if (executor != null) recovered = executor.recover(name,
                            JsonParser.parseString(str(fn, "arguments")).getAsJsonObject());
                } catch (RuntimeException ignored) { }
                result = toolMessage(id, recovered == null
                        ? errorJson("上次调用执行结果未知，禁止直接重放。请核查当前配置和确认项，再决定是否生成新预览。") : recovered);
            } else result = runToolCall(executor, call);
            state.add(result);
            state.startedCalls.remove(id);
            checkpoint.save();
            if (listener != null) listener.onToolCallFinished(name == null ? "unknown" : name, parseToolResult(result));
        }
    }

    private static boolean isReadOnlyTool(String name) {
        return "list_plugin_yaml".equals(name) || "list_yaml_keys".equals(name) || "get_yaml_value".equals(name)
                || "search_yaml_keys".equals(name) || "list_plugins".equals(name) || "search_plugins".equals(name)
                || "get_plugin_metadata".equals(name) || "find_plugin_files".equals(name)
                || "search_yaml_values".equals(name) || "read_yaml_snippet".equals(name);
    }

    /** Close incomplete groups before a new user request or control-event message is appended. */
    public static void closePendingCalls(ToolConversation state) {
        int lastAssistant = -1;
        for (int i = state.messages.size() - 1; i >= 0; i--) {
            if ("assistant".equals(str(state.messages.get(i).getAsJsonObject(), "role"))) { lastAssistant = i; break; }
        }
        if (lastAssistant >= 0) {
            java.util.Set<String> answered = new java.util.HashSet<>();
            for (int j = lastAssistant + 1; j < state.messages.size(); j++) {
                JsonObject message = state.messages.get(j).getAsJsonObject();
                if ("tool".equals(str(message, "role"))) answered.add(str(message, "tool_call_id"));
            }
            JsonArray calls = getToolCalls(state.messages.get(lastAssistant).getAsJsonObject());
            if (calls != null) for (JsonElement call : calls) {
                String id = str(call.getAsJsonObject(), "id");
                if (!answered.contains(id)) state.add(toolMessage(id, errorJson("任务中断，调用未完成或结果未知；请核查后再操作。")));
            }
        }
        state.startedCalls.clear();
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

        return parseContent(postTracked(body));
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

        return parseContent(postTracked(body));
    }

    private JsonObject postTracked(JsonObject body) throws IOException {
        try {
            JsonObject response = HttpJsonClient.postJson(baseUrl + "/chat/completions", apiKey, body);
            JsonObject usage = response != null && response.has("usage") && response.get("usage").isJsonObject()
                    ? response.getAsJsonObject("usage") : null;
            TokenUsageStore.record(model, usage);
            return response;
        } catch (IOException e) {
            TokenUsageStore.record(model, null);
            throw e;
        }
    }

    private static String parseContent(JsonObject resp) {
        JsonObject msg = firstMessage(resp);
        return msg == null ? null : contentOf(msg);
    }

    private static final class StreamAccumulator implements HttpJsonClient.StreamHandler {
        private final ToolStreamListener listener;
        private final StringBuilder content = new StringBuilder();
        private boolean done;
        private String finishReason;
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
            String finish = str(choice, "finish_reason");
            if (finish != null) finishReason = finish;
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
            done = true;
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
