package com.exai.generators;

import com.exai.config.Config;
import com.exai.entity.Answer;
import com.exai.entity.GameDocument;
import com.exai.entity.PlayerQuestion;
import com.exai.i18n.Lang;
import com.exai.service.LLMService;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class AnswerGenerator {
    private LLMService llmService;

    public AnswerGenerator(LLMService llmService) {
        this.llmService = llmService;
    }

    public Answer generateAnswer(PlayerQuestion question, Boolean isFormatWithLineBreak) {
        List<GameDocument> relevantDocs = Config.knowledgeBase.retrieveRelevantDocs(
                question.getQuestion(), question.getContext());

        String prompt = buildSimplePrompt(question, relevantDocs);
        String rawAnswer = llmService.generateResponse(prompt);
        return parseResponse(rawAnswer, relevantDocs, isFormatWithLineBreak);
    }

    public Answer generateBrodcastAnswer(PlayerQuestion question, Boolean isFormatWithLineBreak) {
        List<GameDocument> relevantDocs = Config.knowledgeBase.retrieveRelevantDocs(
                question.getQuestion(), question.getContext());

        String prompt = buildBrodcastPrompt(question, relevantDocs);
        String rawAnswer = llmService.generateResponse(prompt);
        return parseResponse(rawAnswer, relevantDocs, isFormatWithLineBreak);
    }

    private String buildSimplePrompt(PlayerQuestion question, List<GameDocument> docs) {
        StringBuilder prompt = new StringBuilder();
        prompt.append(Lang.get("prompt.base-role", Config.assistantName));

        // 无可用文档：明确指示拒答而非凭自身知识编造
        if (docs == null || docs.isEmpty()) {
            prompt.append(Lang.get("prompt.no-docs-refuse")).append(question.getQuestion());
            return prompt.toString();
        }

        prompt.append(Lang.get("prompt.strict-with-docs"));
        appendDocs(prompt, docs);
        prompt.append(Lang.get("prompt.docs-end"));
        prompt.append(Lang.get("prompt.player-asks")).append(question.getQuestion());
        appendContext(prompt, question);
        // 最强约束放在最末尾（近因效应），强制「无依据则拒答」
        prompt.append(Lang.get("prompt.grounding-reminder"));
        return prompt.toString();
    }

    private String buildBrodcastPrompt(PlayerQuestion question, List<GameDocument> docs) {
        StringBuilder prompt = new StringBuilder();

        if (docs == null || docs.isEmpty()) {
            prompt.append(Lang.get("prompt.broadcast-no-docs", Config.assistantName))
                  .append(question.getQuestion());
            return prompt.toString();
        }

        prompt.append(Lang.get("prompt.broadcast-role", Config.assistantName));
        prompt.append(Lang.get("prompt.strict-with-docs"));
        appendDocs(prompt, docs);
        prompt.append(Lang.get("prompt.docs-end"));
        prompt.append(Lang.get("prompt.player-asks")).append(question.getQuestion());
        appendContext(prompt, question);
        prompt.append(Lang.get("prompt.grounding-reminder"));
        return prompt.toString();
    }

    /** 把检索到的文档以「序号. 内容」逐条附加。 */
    private void appendDocs(StringBuilder prompt, List<GameDocument> docs) {
        for (int i = 0; i < docs.size(); i++) {
            prompt.append(i + 1).append(". ").append(docs.get(i).getContent()).append("\n");
        }
    }

    /** 玩家有当前状态上下文时附加。 */
    private void appendContext(StringBuilder prompt, PlayerQuestion question) {
        if (question.getContext() != null && !question.getContext().isEmpty()) {
            prompt.append(Lang.get("prompt.context", question.getContext()));
        }
    }

    public static String formatWithLineBreak(String input) {
        int charPerLine = Config.config.getInt("charNumPerLine");
        if (input == null || input.isEmpty()) {
            return input;
        }

        StringBuilder result = new StringBuilder();
        int length = input.length();

        for (int i = 0; i < length; i++) {
            result.append(input.charAt(i));

            if ((i + 1) % charPerLine == 0 && i != length - 1) {
                result.append(System.lineSeparator());
            }
        }

        return result.toString();
    }

    private Answer parseResponse(String response, List<GameDocument> sources, Boolean isFormatWithLineBreak) {
        Answer answer = new Answer();
        // 在换行格式化之前判定，避免插入的换行把拒答标记切断
        answer.setUnknown(isNoAnswer(response));
        if (isFormatWithLineBreak) {
            response = formatWithLineBreak(response);
        }
        answer.setAnswer(response);

        if (sources != null) {
            List<String> sourceIds = sources.stream()
                    .map(GameDocument::getId)
                    .collect(Collectors.toList());
            answer.setSources(sourceIds);
        } else {
            answer.setSources(new ArrayList<>());
        }

        if (response.length() < 10) {
            answer.setConfidence(0.5);
        } else {
            answer.setConfidence(0.8);
        }

        answer.setSuggestedAction(getAction(response));
        return answer;
    }

    /** 命中任一拒答标记（如「找不到相关信息」）即视为 AI 无法作答。response 为空也视为无法作答。 */
    private boolean isNoAnswer(String response) {
        if (response == null || response.trim().isEmpty()) {
            return true;
        }
        String lower = response.toLowerCase();
        for (String marker : Lang.get("prompt.no-answer-marker").split(",")) {
            String m = marker.trim().toLowerCase();
            if (!m.isEmpty() && lower.contains(m)) {
                return true;
            }
        }
        return false;
    }

    private String getAction(String response) {
        String lower = response.toLowerCase();

        if (lower.contains(Lang.get("action.kw-go").toLowerCase())) return Lang.get("action.go");
        if (lower.contains(Lang.get("action.kw-use").toLowerCase())) return Lang.get("action.use");
        if (lower.contains(Lang.get("action.kw-talk").toLowerCase())) return Lang.get("action.talk");
        if (lower.contains(Lang.get("action.kw-collect").toLowerCase())) return Lang.get("action.collect");
        if (lower.contains(Lang.get("action.kw-attack").toLowerCase())) return Lang.get("action.attack");
        if (lower.contains(Lang.get("action.kw-quest").toLowerCase())) return Lang.get("action.quest");

        return Lang.get("action.default");
    }
}
