package com.zkry.map.service;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 高德 POI 阶段工具白名单。
 *
 * <p>这个阶段只允许 Agent 做地理编码和景点/商圈 POI 查询，不能顺手查天气或酒店。
 */
@Component
public class AmapGeoPoiTools {

    private final AmapTravelTools delegate;

    public AmapGeoPoiTools(AmapTravelTools delegate) {
        this.delegate = delegate;
    }

    @Tool(name = AmapToolNames.GEOCODE, description = "查询中国城市或地址的高德地理编码，返回 adcode 与经纬度。")
    public String geocode(
        @ToolParam(description = "城市名或地址，例如：昆明、昆明市五华区。", required = true) String city
    ) {
        return delegate.geocode(city);
    }

    @Tool(name = AmapToolNames.POI_SEARCH, description = "按城市和关键词搜索高德 POI，可用于景点、商圈、交通点等候选查询。")
    public String poiSearch(
        @ToolParam(description = "城市名，例如：昆明。", required = true) String city,
        @ToolParam(description = "搜索关键词，例如：昆明 老人 轻松 景点。", required = true) String keywords,
        @ToolParam(description = "最多返回数量，建议 3 到 8。", required = false) Integer limit
    ) {
        return delegate.poiSearch(city, keywords, limit);
    }
}
