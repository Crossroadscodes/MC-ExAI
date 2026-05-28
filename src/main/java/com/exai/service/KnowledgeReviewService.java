package com.exai.service;

import com.exai.entity.ReviewResult;
import com.exai.i18n.Lang;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 知识 AI 初审：结合问题判断一条问答是否适合作为游戏知识库的一条知识，
 * 过滤玩笑、调侃、辱骂、广告、答非所问、明显错误或无意义内容。二元判定，不打分。
 *
 * <p>玩家书本提交与公屏自动采集共用本服务：
 * <ul>
 *   <li>返回 {@code null} = AI 不可达 / 输出无法解析（与「判否」区分开）；</li>
 *   <li>{@code pass=false} = AI 判定不适合入库；</li>
 *   <li>{@code pass=true} = 通过。</li>
 * </ul>
 */
public class KnowledgeReviewService {
    private final LLMService llm;

    public KnowledgeReviewService(LLMService llm) {
        this.llm = llm;
    }

    /**
     * @param question    问题
     * @param answer      回答
     * @param thankedHint 提问者随后是否表达了感谢（仅作为提示加入 prompt，帮助判断）
     * @return 初审结果；AI 调用或解析失败时返回 {@code null}
     */
    public ReviewResult review(String question, String answer, boolean thankedHint) {
        String prompt = thankedHint
                ? Lang.get("review.thanks-hint") + Lang.get("review.prompt", question, answer)
                : Lang.get("review.prompt", question, answer);
        String raw = llm.complete(prompt, 0.2, 200);
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        return parse(raw);
    }

    private ReviewResult parse(String raw) {
        try {
            String json = extractJson(raw);
            if (json == null) {
                return null;
            }
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            if (!obj.has("pass")) {
                return null;
            }
            boolean pass = obj.get("pass").getAsBoolean();
            String reason = obj.has("reason") && !obj.get("reason").isJsonNull()
                    ? obj.get("reason").getAsString() : "";
            return new ReviewResult(pass, reason);
        } catch (Exception e) {
            System.err.println("Knowledge review parse error: " + e.getMessage() + " raw=" + raw);
            return null;
        }
    }

    /** 容错地从模型输出中截取 JSON 主体（去掉 ```json 代码块包裹或前后多余文字）。 */
    private String extractJson(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end < 0 || end <= start) {
            return null;
        }
        return raw.substring(start, end + 1);
    }
}
