package com.exai.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Durable execution ledger; metadata is removed before sending messages to the model. */
public final class ToolConversation {
    public JsonArray messages = new JsonArray();
    public String taskId = "";
    public String status = "IDLE";
    public String reason = "";
    public String cwd = "";
    public String pendingInput = "";
    public long turnId;
    public int iterations;
    public int totalIterations;
    public String summary = "";
    public int summaryThrough;
    public int summaryCalls;
    public java.util.List<String> startedCalls = new java.util.ArrayList<>();

    public boolean canContinue() {
        return "PAUSED".equals(status) || "INTERRUPTED".equals(status) || "FAILED".equals(status);
    }

    public void add(JsonObject message) {
        JsonObject copy = message.deepCopy();
        copy.addProperty("_turnId", turnId);
        messages.add(copy);
    }

    public void addText(String role, String text) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", text);
        add(message);
    }
}
