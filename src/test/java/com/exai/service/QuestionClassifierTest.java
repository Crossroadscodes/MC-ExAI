package com.exai.service;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class QuestionClassifierTest {

    @Test
    public void parsesBooleanAndJsonResponses() {
        assertEquals(QuestionClassifier.Result.QUESTION, QuestionClassifier.parse("true"));
        assertEquals(QuestionClassifier.Result.NOT_QUESTION, QuestionClassifier.parse("false"));
        assertEquals(QuestionClassifier.Result.NOT_QUESTION,
                QuestionClassifier.parse("{\"isQuestion\" : false}"));
        assertEquals(QuestionClassifier.Result.QUESTION,
                QuestionClassifier.parse("```json\n{\n  \"isQuestion\": true\n}\n```"));
    }

    @Test
    public void rejectsUntrustedResponses() {
        assertEquals(QuestionClassifier.Result.UNAVAILABLE, QuestionClassifier.parse(null));
        assertEquals(QuestionClassifier.Result.UNAVAILABLE, QuestionClassifier.parse(""));
        assertEquals(QuestionClassifier.Result.UNAVAILABLE,
                QuestionClassifier.parse("{\"isQuestion\": \"false\"}"));
        assertEquals(QuestionClassifier.Result.UNAVAILABLE,
                QuestionClassifier.parse("{\"is_question\": false}"));
        assertEquals(QuestionClassifier.Result.UNAVAILABLE,
                QuestionClassifier.parse("这不是一个问题"));
    }

    @Test
    public void classifiesOnlyExplicitTrustedResults() {
        assertEquals(QuestionClassifier.Result.NOT_QUESTION,
                new QuestionClassifier(new StubLlm("true")).classify("   "));
        assertEquals(QuestionClassifier.Result.NOT_QUESTION,
                new QuestionClassifier(new StubLlm("{\"isQuestion\":false}")).classify("没啥用"));
        assertEquals(QuestionClassifier.Result.UNAVAILABLE,
                new QuestionClassifier(new StubLlm(null)).classify("没啥用"));
    }

    private static final class StubLlm extends LLMService {
        private final String response;

        StubLlm(String response) {
            super("test-key", "https://example.invalid/v1", "test-model", 0.0);
            this.response = response;
        }

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public String complete(String prompt, double temperature, int maxTokens) {
            return response;
        }
    }
}
