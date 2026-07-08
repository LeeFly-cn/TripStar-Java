package com.zkry.content.service;

/**
 * 暴露给 ReactAgent 的小红书工具名称。
 *
 * <p>小红书没有官方 MCP，本项目把 Java service 包装成 Spring AI method tool，
 * 让小红书阶段 Agent 能按这些名字调用搜索、详情和城市上下文采集。
 */
public final class XhsToolNames {

    public static final String SEARCH_NOTES = "xhs_search_notes";
    public static final String NOTE_DETAIL = "xhs_note_detail";

    private XhsToolNames() {
    }
}
