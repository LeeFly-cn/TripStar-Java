package com.zkry.map.dto;

import java.util.List;
import java.util.Optional;

/**
 * 高德地图上下文。
 *
 * <p>它是 ResearchAgent 或地图 service 采集后的统一结果，包含城市级 POI、酒店、
 * 餐饮、天气和中心坐标。PlannerAgent 只消费这个结构，不关心底层 HTTP 细节。
 */
public record MapPlanningContext(
    List<MapCityContext> cities,
    boolean realData,
    String source,
    String message
) {
    public static MapPlanningContext empty(String source, String message) {
        return new MapPlanningContext(List.of(), false, source, message);
    }

    public List<MapCityContext> safeCities() {
        return cities == null ? List.of() : cities;
    }

    public Optional<MapCityContext> findCity(String city) {
        if (city == null || city.isBlank()) {
            return Optional.empty();
        }
        return safeCities().stream()
            .filter(context -> city.equals(context.city()))
            .findFirst();
    }
}
