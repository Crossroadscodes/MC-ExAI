package com.exai.entity;

import java.util.List;

/**
 * 离线提交者待领取奖励的载体：物品(形如 "MATERIAL:amount")与登录时提示消息。
 * 金币奖励在采纳时已通过 Vault 离线发放，不进入此队列。
 */
public class PendingReward {
    private final List<String> items;
    private final List<String> messages;

    public PendingReward(List<String> items, List<String> messages) {
        this.items = items;
        this.messages = messages;
    }

    public List<String> getItems() {
        return items;
    }

    public List<String> getMessages() {
        return messages;
    }

    public boolean isEmpty() {
        return items.isEmpty() && messages.isEmpty();
    }
}
