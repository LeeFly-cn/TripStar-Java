package com.zkry.trip.dto;

import com.zkry.content.dto.ContentPlanningContext;
import java.util.List;

/**
 * 小红书详情 Agent 的结构化输出。
 *
 * <p>该 Agent 只读取笔记详情并提炼游记内容，不应该携带地图、天气或酒店字段。
 */
public record XhsDetailResearchResult(
    ContentPlanningContext content_context,
    List<String> excluded_places,
    List<String> tool_calls,
    String summary
) {
    public List<String> safeExcludedPlaces() {
        return excluded_places == null ? List.of() : excluded_places;
    }

    public List<String> safeToolCalls() {
        return tool_calls == null ? List.of() : tool_calls;
    }

    public String safeSummary() {
        if (summary != null && !summary.isBlank()) {
            return summary;
        }
        return content_context == null || content_context.message() == null ? "" : content_context.message();
    }
}
