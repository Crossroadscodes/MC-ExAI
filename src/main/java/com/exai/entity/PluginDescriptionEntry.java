package com.exai.entity;

import lombok.Getter;
import lombok.Setter;

/**
 * 一个服务器插件的描述条目，持久化在 plugins/ExAI/knowledge/pluginDesc.yml。
 * 仅 enabled=true 且 description 非空的条目，会作为知识参与 AI 回答。
 */
@Getter
@Setter
public class PluginDescriptionEntry {
    private String name;
    private String version = "";
    /** 上次扫描时该插件是否在线（已安装） */
    private boolean installed = true;
    /** 是否纳入 RAG */
    private boolean enabled = true;
    /** 管理员填写的描述 */
    private String description = "";

    public PluginDescriptionEntry() {
    }

    public PluginDescriptionEntry(String name, String version, boolean installed, boolean enabled, String description) {
        this.name = name;
        this.version = version == null ? "" : version;
        this.installed = installed;
        this.enabled = enabled;
        this.description = description == null ? "" : description;
    }
}
