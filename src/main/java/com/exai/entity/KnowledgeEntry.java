package com.exai.entity;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class KnowledgeEntry {
    private String question;
    private String answer;
    private String submitter;
    private long timestamp;
    /** 来源：player=玩家手动提交，auto=公屏自动采集 */
    private String source = "player";
    /** 提问者是否对该回答表达过感谢（仅自动采集有意义，供管理员复核参考） */
    private boolean thanked = false;

    public KnowledgeEntry(String question, String answer, String submitter, long timestamp) {
        this.question = question;
        this.answer = answer;
        this.submitter = submitter;
        this.timestamp = timestamp;
    }
}