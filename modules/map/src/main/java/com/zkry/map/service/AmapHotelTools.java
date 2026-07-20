package com.zkry.map.service;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 高德酒店阶段工具白名单。
 *
 * <p>酒店和餐饮通常一起影响住宿区域选择，所以放在同一个阶段。
 */
@Component
public class AmapHotelTools {

    private final AmapTravelTools delegate;

    public AmapHotelTools(AmapTravelTools delegate) {
        this.delegate = delegate;
    }

    @Tool(name = AmapToolNames.HOTEL_SEARCH, description = "按城市和住宿偏好搜索高德酒店 POI。")
    public String hotelSearch(
        @ToolParam(description = "城市名，例如：昆明。", required = true) String city,
        @ToolParam(description = "住宿偏好，例如：住得方便一点、舒适型酒店、地铁附近。", required = false) String accommodation,
        @ToolParam(description = "最多返回数量，建议 3 到 8。", required = false) Integer limit
    ) {
        return delegate.hotelSearch(city, accommodation, limit);
    }

    @Tool(name = AmapToolNames.RESTAURANT_SEARCH, description = "按城市和美食关键词搜索高德餐饮 POI。")
    public String restaurantSearch(
        @ToolParam(description = "城市名，例如：昆明。", required = true) String city,
        @ToolParam(description = "餐饮关键词，例如：特色美食、早餐、米线。", required = false) String keywords,
        @ToolParam(description = "最多返回数量，建议 3 到 8。", required = false) Integer limit
    ) {
        return delegate.restaurantSearch(city, keywords, limit);
    }

}
