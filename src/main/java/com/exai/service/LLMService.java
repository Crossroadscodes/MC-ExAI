package com.exai.service;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;

public class LLMService {
    private OpenAIClient client;
    private String model;

    public LLMService(String apiKey, String baseUrl, String model) {
        this.client = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .build();
        this.model = model;
    }

    public String generateResponse(String prompt) {
        try {
            ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                    .addUserMessage(prompt)
                    .model(model)
                    .temperature(0.7)
                    .maxTokens(500)
                    .build();

            ChatCompletion response = client.chat().completions().create(params);

            if (response != null &&
                    response.choices() != null &&
                    !response.choices().isEmpty()) {
                return response.choices().get(0).message().content()
                        .orElse("助手无响应");
            }

            return "助手无响应";

        } catch (Exception e) {
            System.err.println("LLM调用错误: " + e.getMessage());
            return "系统错误";
        }
    }
}