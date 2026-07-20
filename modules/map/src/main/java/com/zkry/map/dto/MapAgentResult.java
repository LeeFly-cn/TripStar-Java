package com.zkry.map.dto;

import java.util.List;

/**
 * 地图类 Agent 的结构化输出。
 *
 * <p>POI、天气、酒店餐饮 Agent 都只负责产生地图数据，因此不再输出包含
 * 小红书正文、用户约束等无关字段的通用研究 DTO。
 */
public record MapAgentResult(
    MapPlanningContext map_context,
    List<String> tool_calls,
    String summary
) {
    public List<String> safeToolCalls() {
        return tool_calls == null ? List.of() : tool_calls;
    }

    public String safeSummary() {
        if (summary != null && !summary.isBlank()) {
            return summary;
        }
        return map_context == null || map_context.message() == null ? "" : map_context.message();
    }
}
