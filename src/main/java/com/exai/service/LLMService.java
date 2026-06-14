package com.exai.service;

import com.exai.utils.HttpJsonClient;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

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
        if (resp != null && resp.has("choices")) {
            JsonArray choices = resp.getAsJsonArray("choices");
            if (choices.size() > 0) {
                JsonObject first = choices.get(0).getAsJsonObject();
                if (first.has("message")) {
                    JsonObject msg = first.getAsJsonObject("message");
                    if (msg.has("content") && !msg.get("content").isJsonNull()) {
                        return msg.get("content").getAsString();
                    }
                }
            }
        }
        return null;
    }

    private static String stripTrailingSlash(String s) {
        if (s == null) return "";
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
