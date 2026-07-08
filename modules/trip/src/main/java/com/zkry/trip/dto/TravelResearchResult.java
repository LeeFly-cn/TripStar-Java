package com.zkry.trip.dto;

import com.zkry.content.dto.ContentPlanningContext;
import com.zkry.map.dto.MapPlanningContext;
import com.zkry.trip.constant.TravelResearchMessages;
import java.util.List;

/**
 * 资料研究阶段 Agent 的结构化输出。
 *
 * <p>这是多 Agent 流程里的第一份“研究报告”：map_context 来自高德工具，
 * content_context 来自小红书工具或 service 合并结果，user_constraints 和
 * excluded_places 用来记录用户的软硬约束，比如“不想太累”“不要滇池”。
 */
public record TravelResearchResult(
    MapPlanningContext map_context,
    ContentPlanningContext content_context,
    List<String> user_constraints,
    List<String> excluded_places,
    List<String> tool_calls,
    String summary
) {
    public List<String> safeToolCalls() {
        return tool_calls == null ? List.of() : tool_calls;
    }

    public String safeSummary() {
        return summary == null || summary.isBlank() ? TravelResearchMessages.AGENT_SUMMARY_EMPTY : summary;
    }
}
