package com.zkry.trip.constant;

import com.zkry.content.dto.ContentPlanningContext;

/**
 * 旅行资料研究阶段的提示文案。
 *
 * <p>这里只保留错误提示文本，不再创建空上下文。Agent 少返回字段或工具无数据时，
 * 由 {@code TripResearchService} 直接失败，避免把真实问题包装成“空结果”继续往后跑。
 */
public final class TravelResearchMessages {

    public static final String XHS_SEARCH_NO_NOTES = "小红书搜索阶段没有返回可读取的笔记。";
    public static final String XHS_CONTENT_NO_REAL_DATA = "小红书内容阶段没有获取到真实游记内容。";
    public static final String AGENT_SUMMARY_EMPTY = "研究智能体未返回摘要。";

    private TravelResearchMessages() {
    }

    public static String bothMessage(ContentPlanningContext serviceContent, ContentPlanningContext toolContent) {
        return "小红书 both 模式：service="
            + safe(serviceContent == null ? "" : serviceContent.message())
            + "；tool="
            + safe(toolContent == null ? "" : toolContent.message());
    }

    public static String xhsSearchNoNotes(String detail) {
        return XHS_SEARCH_NO_NOTES + " " + safe(detail);
    }

    public static String xhsContentNoRealData(ContentPlanningContext context) {
        return XHS_CONTENT_NO_REAL_DATA + " " + safe(context == null ? "" : context.message());
    }

    public static String xhsBothNoRealData(ContentPlanningContext serviceContent, ContentPlanningContext toolContent) {
        return XHS_CONTENT_NO_REAL_DATA
            + " service=" + safe(serviceContent == null ? "" : serviceContent.message())
            + "；tool=" + safe(toolContent == null ? "" : toolContent.message());
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "无详细原因" : value;
    }
}
