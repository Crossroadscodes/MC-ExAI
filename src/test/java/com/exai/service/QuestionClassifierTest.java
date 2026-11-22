package com.exai.service;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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

    @Test
    public void contextualRewriteOnlyAcceptsCompleteStandaloneQuestion() {
        StubLlm llm = new StubLlm("{\"isQuestion\":true,\"standaloneQuestion\":\"如何打开服务器商店？\"}");
        QuestionClassifier.ContextualResult result = new QuestionClassifier(llm)
                .rewriteWithContext("那怎么打开？", "玩家A: 服务器商店在哪？");
        assertEquals(QuestionClassifier.Result.QUESTION, result.getResult());
        assertEquals("如何打开服务器商店？", result.getStandaloneQuestion());

        result = new QuestionClassifier(new StubLlm("{\"isQuestion\":false,\"standaloneQuestion\":\"\"}"))
                .rewriteWithContext("算了", "玩家A: 服务器商店在哪？");
        assertEquals(QuestionClassifier.Result.NOT_QUESTION, result.getResult());
        assertTrue(result.getStandaloneQuestion().isEmpty());
    }

    @Test
    public void rejectsUnchangedOrPunctuationOnlyRewrites() {
        QuestionClassifier classifier = new QuestionClassifier(
                new StubLlm("{\"isQuestion\":true,\"standaloneQuestion\":\"?????\"}"));
        QuestionClassifier.ContextualResult result = classifier.rewriteWithContext(
                "?????", "??A: ????????");
        assertEquals(QuestionClassifier.Result.UNAVAILABLE, result.getResult());
        assertTrue(result.getStandaloneQuestion().isEmpty());
    }

    @Test
    public void rechecksRewrittenQuestionWithoutContext() {
        for (QuestionClassifier.Result validation : QuestionClassifier.Result.values()) {
            final String[] checked = {null};
            QuestionClassifier classifier = new QuestionClassifier(null) {
                @Override
                public ContextualResult rewriteWithContext(String input, String context) {
                    return new ContextualResult(Result.QUESTION, "How to open the shop?");
                }

                @Override
                public Result classify(String input) {
                    checked[0] = input;
                    return validation;
                }
            };
            QuestionClassifier.ContextualResult result = classifier.rewriteAndValidate("How?", "Shop");
            assertEquals("How to open the shop?", checked[0]);
            assertEquals(validation, result.getResult());
            assertEquals(validation == QuestionClassifier.Result.QUESTION ? "How to open the shop?" : "",
                    result.getStandaloneQuestion());
        }
    }

    @Test
    public void doesNotRecheckFailedRewrite() {
        QuestionClassifier classifier = new QuestionClassifier(null) {
            @Override
            public ContextualResult rewriteWithContext(String input, String context) {
                return new ContextualResult(Result.NOT_QUESTION, "");
            }

            @Override
            public Result classify(String input) {
                throw new AssertionError("Failed rewrites must not be classified");
            }
        };
        assertEquals(QuestionClassifier.Result.NOT_QUESTION,
                classifier.rewriteAndValidate("How?", "Shop").getResult());
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
