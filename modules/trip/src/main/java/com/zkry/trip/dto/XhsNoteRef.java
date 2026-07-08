package com.zkry.trip.dto;

/**
 * 小红书搜索阶段返回的笔记引用。
 *
 * <p>搜索 Agent 只负责找到值得继续阅读的笔记；详情 Agent 会根据这里的
 * {@code note_id/xsec_token} 再调用详情工具。把中间结果结构化出来，前端进度和
 * 后端日志都能看清楚 Agent 不是一步糊过去的。
 */
public record XhsNoteRef(
    String note_id,
    String title,
    String xsec_token,
    String xsec_source,
    String liked_count
) {
}
