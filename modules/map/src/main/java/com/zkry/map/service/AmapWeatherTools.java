package com.zkry.map.service;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 高德天气阶段工具白名单。
 */
@Component
public class AmapWeatherTools {

    private final AmapTravelTools delegate;

    public AmapWeatherTools(AmapTravelTools delegate) {
        this.delegate = delegate;
    }

    @Tool(name = AmapToolNames.WEATHER, description = "查询高德天气预报，用于规划每日衣物、户外/室内安排。")
    public String weather(
        @ToolParam(description = "城市名，例如：昆明。", required = true) String city
    ) {
        return delegate.weather(city);
    }
}
