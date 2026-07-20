package com.zkry.map.service;

import java.util.List;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 指定笔记模式的酒店餐饮 Tool 白名单。
 *
 * <p>该阶段只允许一次酒店搜索和批量餐饮搜索，不暴露单次餐饮 Tool，
 * 防止 Agent 再次组织十几次 function call。关键词仍由 Agent 根据每日路线自主决定。
 */
@Component
public class XhsNoteHotelFoodTools {

    private final AmapTravelTools delegate;

    public XhsNoteHotelFoodTools(AmapTravelTools delegate) {
        this.delegate = delegate;
    }

    @Tool(name = AmapToolNames.HOTEL_SEARCH, description = "按城市和住宿偏好搜索高德酒店 POI。")
    public String hotelSearch(
        @ToolParam(description = "城市名，例如：昆明。", required = true) String city,
        @ToolParam(description = "住宿偏好和路线位置要求。", required = false) String accommodation,
        @ToolParam(description = "最多返回数量，指定笔记模式建议使用 1。", required = false) Integer limit
    ) {
        return delegate.hotelSearch(city, accommodation, limit);
    }

    @Tool(
        name = AmapToolNames.RESTAURANT_BATCH_SEARCH,
        description = "按城市批量搜索多组高德餐饮 POI。适合一次查询多天早餐、午餐和晚餐。"
    )
    public String restaurantBatchSearch(
        @ToolParam(description = "城市名，例如：昆明。", required = true) String city,
        @ToolParam(
            description = "餐饮关键词列表，单次最多20组。例如：[长水机场附近早餐, 陆军讲武堂附近午餐, 野生动物园附近晚餐]。",
            required = true
        ) List<String> keywords,
        @ToolParam(description = "每组关键词最多返回数量，指定笔记模式建议使用 1。", required = false) Integer limitPerKeyword
    ) {
        return delegate.restaurantBatchSearch(city, keywords, limitPerKeyword);
    }
}
