package com.exai.service;

import com.exai.utils.HttpJsonClient;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public class LLMService {
    private final String apiKey;
    private final String baseUrl;
    private final String model;

    public LLMService(String apiKey, String baseUrl, String model) {
        this.apiKey = apiKey;
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.model = model;
    }

    public String generateResponse(String prompt) {
        try {
            JsonObject body = new JsonObject();
            body.addProperty("model", model);
            body.addProperty("temperature", 0.7);
            body.addProperty("max_tokens", 500);

            JsonArray messages = new JsonArray();
            JsonObject userMsg = new JsonObject();
            userMsg.addProperty("role", "user");
            userMsg.addProperty("content", prompt);
            messages.add(userMsg);
            body.add("messages", messages);

            JsonObject resp = HttpJsonClient.postJson(
                    baseUrl + "/chat/completions", apiKey, body);

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
            return com.exai.i18n.Lang.get("service.llm.empty");
        } catch (Exception e) {
            System.err.println("LLM call error: " + e.getMessage());
            return com.exai.i18n.Lang.get("service.llm.error");
        }
    }

    private static String stripTrailingSlash(String s) {
        if (s == null) return "";
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
