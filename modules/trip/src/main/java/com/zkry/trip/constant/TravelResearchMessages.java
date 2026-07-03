package com.zkry.trip.constant;

import com.zkry.common.core.constant.TravelDataSource;
import com.zkry.content.dto.ContentPlanningContext;
import com.zkry.map.dto.MapPlanningContext;
import com.zkry.trip.dto.TravelResearchResult;
import java.util.List;

/**
 * 旅行资料研究阶段的默认上下文与提示文案。
 *
 * <p>把这些内容集中在这里，是为了避免在 Agent 编排代码里到处散落
 * {@code "agent-tool"}、{@code "xhs-service"} 和固定中文提示。后续如果要做国际化、
 * 错误码化或统一前端展示文案，优先从这里改。
 */
public final class TravelResearchMessages {

    public static final String XHS_SERVICE_DISABLED = "小红书 service 模式未启用。";
    public static final String AGENT_MAP_CONTEXT_MISSING = "研究智能体未返回地图上下文。";
    public static final String AGENT_CONTENT_CONTEXT_MISSING = "研究智能体未返回小红书上下文。";
    public static final String AGENT_RESULT_MISSING = "研究智能体未返回结构化结果。";
    public static final String AGENT_SUMMARY_FALLBACK = "研究智能体已完成工具调用。";

    private TravelResearchMessages() {
    }

    public static ContentPlanningContext xhsServiceDisabled() {
        return ContentPlanningContext.empty(TravelDataSource.XHS_SERVICE, XHS_SERVICE_DISABLED);
    }

    public static ContentPlanningContext xhsServiceFailed(String reason) {
        return ContentPlanningContext.empty(
            TravelDataSource.XHS_SERVICE,
            "小红书 service 采集失败：" + safe(reason)
        );
    }

    public static MapPlanningContext agentMapContextMissing() {
        return MapPlanningContext.empty(TravelDataSource.AGENT_TOOL, AGENT_MAP_CONTEXT_MISSING);
    }

    public static ContentPlanningContext agentContentContextMissing() {
        return ContentPlanningContext.empty(TravelDataSource.AGENT_TOOL, AGENT_CONTENT_CONTEXT_MISSING);
    }

    public static TravelResearchResult agentResultMissing() {
        return new TravelResearchResult(
            agentMapContextMissing(),
            agentContentContextMissing(),
            List.of(),
            List.of(),
            List.of(),
            AGENT_RESULT_MISSING
        );
    }

    public static String bothMessage(ContentPlanningContext serviceContent, ContentPlanningContext toolContent) {
        return "小红书 both 模式：service="
            + safe(serviceContent == null ? "" : serviceContent.message())
            + "；tool="
            + safe(toolContent == null ? "" : toolContent.message());
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "无详细原因" : value;
    }
}
