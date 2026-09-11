package com.exai.utils;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class HttpJsonClient {
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 60_000;

    private HttpJsonClient() {}

    public interface StreamHandler {
        void onData(JsonObject data) throws IOException;
        void onDone() throws IOException;
    }

    public static JsonObject postJson(String url, String bearerToken, JsonObject body) throws IOException {
        HttpURLConnection conn = openPost(url, bearerToken, "application/json");
        writePayload(conn, body);

        int status = conn.getResponseCode();
        String resp = readResponse(conn, status);
        if (status < 200 || status >= 300) {
            throw new IOException("HTTP " + status + ": " + resp);
        }
        if (resp.isEmpty()) {
            return null;
        }
        return JsonParser.parseString(resp).getAsJsonObject();
    }

    public static void postJsonStream(String url, String bearerToken, JsonObject body, StreamHandler handler) throws IOException {
        HttpURLConnection conn = openPost(url, bearerToken, "text/event-stream");
        writePayload(conn, body);

        int status = conn.getResponseCode();
        if (status < 200 || status >= 300) {
            throw new IOException("HTTP " + status + ": " + readResponse(conn, status));
        }
        InputStream stream = conn.getInputStream();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith(":")) {
                    continue;
                }
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if (data.isEmpty()) {
                    continue;
                }
                if ("[DONE]".equals(data)) {
                    if (handler != null) {
                        handler.onDone();
                    }
                    return;
                }
                if (handler != null) {
                    handler.onData(JsonParser.parseString(data).getAsJsonObject());
                }
            }
        }
        if (handler != null) {
            handler.onDone();
        }
    }

    private static HttpURLConnection openPost(String url, String bearerToken, String accept) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Accept", accept);
        if (bearerToken != null && !bearerToken.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
        }
        conn.setDoOutput(true);
        return conn;
    }

    private static void writePayload(HttpURLConnection conn, JsonObject body) throws IOException {
        byte[] payload = (body == null ? "{}" : body.toString()).getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload);
        }
    }

    private static String readResponse(HttpURLConnection conn, int status) throws IOException {
        InputStream stream = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
        if (stream == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }
}
