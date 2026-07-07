package com.zkry.content.service;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 小红书详情阶段工具白名单。
 *
 * <p>详情 Agent 只能根据上一阶段搜索出的笔记读取正文和首图，然后把真实游记内容
 * 提炼为 {@code ContentPlanningContext}。
 */
@Component
public class XhsDetailTools {

    private final XhsTravelTools delegate;

    public XhsDetailTools(XhsTravelTools delegate) {
        this.delegate = delegate;
    }

    @Tool(name = XhsToolNames.NOTE_DETAIL, description = "获取小红书笔记详情正文和首图，用于理解真实游记内容。")
    public String noteDetail(
        @ToolParam(description = "小红书笔记 id。", required = true) String noteId,
        @ToolParam(description = "搜索结果里的 xsec_token。", required = false) String xsecToken,
        @ToolParam(description = "xsec_source，默认 pc_search。", required = false) String xsecSource
    ) {
        return delegate.noteDetail(noteId, xsecToken, xsecSource);
    }
}
