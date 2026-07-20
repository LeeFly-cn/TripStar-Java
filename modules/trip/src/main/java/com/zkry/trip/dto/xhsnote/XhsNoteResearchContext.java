package com.zkry.trip.dto.xhsnote;

import com.zkry.content.dto.ContentPlanningContext;
import com.zkry.map.dto.MapPlanningContext;
import com.zkry.trip.dto.TripRequest;

/**
 * 指定笔记资料研究阶段返回给任务服务的完整上下文。
 *
 * @param tripRequest 从笔记推导出的标准旅行请求，后续 Planner 直接复用
 * @param contentContext 笔记事实和景点候选
 * @param mapContext 高德校验、天气及可选酒店餐饮补充结果
 * @param understanding 原始多模态结构化结果，保留用于调试或后续扩展
 */
public record XhsNoteResearchContext(
    TripRequest tripRequest,
    ContentPlanningContext contentContext,
    MapPlanningContext mapContext,
    XhsNoteUnderstandingResult understanding
) {
}
