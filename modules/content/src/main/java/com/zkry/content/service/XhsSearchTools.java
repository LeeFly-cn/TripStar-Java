package com.zkry.content.service;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 小红书搜索阶段工具白名单。
 *
 * <p>它只暴露 {@link XhsToolNames#SEARCH_NOTES}，因此 XhsSearchAgent 无法提前读取
 * 详情或绕过阶段顺序。真正的 HTTP 和签名逻辑仍复用 {@link XhsTravelTools}。
 */
@Component
public class XhsSearchTools {

    private final XhsTravelTools delegate;

    public XhsSearchTools(XhsTravelTools delegate) {
        this.delegate = delegate;
    }

    @Tool(name = XhsToolNames.SEARCH_NOTES, description = "搜索小红书笔记，返回笔记 id、标题、xsec_token 和点赞数。")
    public String searchNotes(
        @ToolParam(description = "搜索关键词，例如：昆明 老人 轻松 景点攻略。", required = true) String keyword,
        @ToolParam(description = "页码，从 1 开始。", required = false) Integer page,
        @ToolParam(description = "最多返回数量，建议 3 到 10。", required = false) Integer pageSize
    ) {
        return delegate.searchNotes(keyword, page, pageSize);
    }
}
