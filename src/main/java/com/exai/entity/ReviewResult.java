package com.exai.entity;

import lombok.Getter;

/**
 * 知识 AI 初审结果（二元判定，不打分）。
 * pass：AI 是否认为这条问答适合作为知识库的一条知识；
 * reason：AI 给出的简短理由，便于管理员复核或反馈给玩家。
 *
 * <p>注意：当 AI 调用失败或输出无法解析时，{@code KnowledgeReviewService.review} 返回 {@code null}
 * 以区分「AI 不可用」与「AI 判定不通过」，调用方据此决定 fail-closed(拦截) 或丢弃。
 */
@Getter
public class ReviewResult {
    private final boolean pass;
    private final String reason;

    public ReviewResult(boolean pass, String reason) {
        this.pass = pass;
        this.reason = reason;
    }
}
