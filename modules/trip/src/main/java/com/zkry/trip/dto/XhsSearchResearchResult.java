package com.zkry.trip.dto;

import java.util.List;

/**
 * XhsSearchAgent 的结构化输出。
 *
 * <p>这个 DTO 不直接进入 PlannerAgent，而是作为小红书详情阶段的输入。这样可以
 * 严格保证流程顺序：先搜索笔记，再根据搜索结果读取详情。
 */
public record XhsSearchResearchResult(
    List<XhsCitySearchResult> cities,
    List<String> user_constraints,
    List<String> excluded_places,
    List<String> tool_calls,
    String summary
) {
    public List<XhsCitySearchResult> safeCities() {
        return cities == null ? List.of() : cities;
    }

    public List<String> safeUserConstraints() {
        return user_constraints == null ? List.of() : user_constraints;
    }

    public List<String> safeExcludedPlaces() {
        return excluded_places == null ? List.of() : excluded_places;
    }

    public List<String> safeToolCalls() {
        return tool_calls == null ? List.of() : tool_calls;
    }

    public String safeSummary() {
        return summary == null || summary.isBlank() ? "小红书搜索阶段已完成。" : summary;
    }
}
