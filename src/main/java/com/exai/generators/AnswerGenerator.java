package com.exai.generators;

import com.exai.config.Config;
import com.exai.entity.Answer;
import com.exai.entity.GameDocument;
import com.exai.entity.PlayerQuestion;
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

        prompt.append("你是一个MC游戏助手，你叫" + Config.assistantName + "，你需要解答玩家在游戏中遇到的问题，回答的时候不要带*等特殊符号，不要换行");

        if (docs != null && !docs.isEmpty()) {
            prompt.append("严格参考以下搜索到的信息回答，如果没有依据一定不要编造回答，直接回复'抱歉，我找不到相关信息，联系服务器管理员补充相关知识'：\n");
            for (int i = 0; i < docs.size(); i++) {
                GameDocument doc = docs.get(i);
                prompt.append(i + 1).append(". ").append(doc.getContent()).append("\n");
            }
        }

        prompt.append("\n玩家问：").append(question.getQuestion());

        if (question.getContext() != null && !question.getContext().isEmpty()) {
            prompt.append("（当前状态：").append(question.getContext()).append("）");
        }

        prompt.append("\n请用中文回答：");

        return prompt.toString();
    }
    private String buildBrodcastPrompt(PlayerQuestion question, List<GameDocument> docs) {
        StringBuilder prompt = new StringBuilder();

        if (docs == null || docs.isEmpty()) {
            prompt.append("你是一个MC游戏助手，你叫" + Config.assistantName + "。当玩家提问时，如果不知道答案，请直接回复'抱歉，我找不到相关信息'。回答尽量精简，不超过100字。\n请用中文回答：\n玩家问：").append(question.getQuestion());
            return prompt.toString();
        }

        prompt.append("你是一个MC游戏助手，你叫" + Config.assistantName + "，你需要解答玩家在游戏中遇到的问题，回答的时候不要带*等特殊符号，不要换行，回答尽量精简，不超过100字");

        if (docs != null && !docs.isEmpty()) {
            prompt.append("参考以下信息回答，如果没有依据不要编造回答：\n");
            for (int i = 0; i < docs.size(); i++) {
                GameDocument doc = docs.get(i);
                prompt.append(i + 1).append(". ").append(doc.getContent()).append("\n");
            }
        }

        prompt.append("\n玩家问：").append(question.getQuestion());

        if (question.getContext() != null && !question.getContext().isEmpty()) {
            prompt.append("（当前状态：").append(question.getContext()).append("）");
        }

        prompt.append("\n请用中文回答：");

        return prompt.toString();
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
        if(isFormatWithLineBreak){
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

    private String getAction(String response) {
        String lower = response.toLowerCase();

        if (lower.contains("去")) return "前往";
        if (lower.contains("使用")) return "使用物品";
        if (lower.contains("对话")) return "与NPC交谈";
        if (lower.contains("收集")) return "收集物品";
        if (lower.contains("攻击")) return "战斗";
        if (lower.contains("任务")) return "完成任务";

        return "继续探索";
    }
}