package com.zkry.map.service;

import com.zkry.common.core.constant.TravelDataSource;
import com.zkry.common.core.constant.TravelToolResponseFields;
import com.zkry.common.json.utils.JsonUtils;
import com.zkry.map.dto.MapCityRequest;
import com.zkry.map.dto.MapCityContext;
import com.zkry.map.dto.MapPoi;
import com.zkry.map.dto.MapWeatherForecast;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 高德地图工具集合，供 ReactAgent 通过 methodTools 调用。
 *
 * <p>工具方法返回 JSON 字符串而不是 Java 对象，是为了让 LLM 看到稳定、可读的
 * {@code success/source/tool/data/error} 结构。真正的 HTTP 调用仍放在
 * {@link AmapMapContextService}，这个类只负责把能力暴露为 Spring AI Tool。
 */
@Component
public class AmapTravelTools {

    private static final Logger log = LoggerFactory.getLogger(AmapTravelTools.class);

    private final AmapMapContextService amapMapContextService;

    public AmapTravelTools(AmapMapContextService amapMapContextService) {
        this.amapMapContextService = amapMapContextService;
    }

    @Tool(name = AmapToolNames.GEOCODE, description = "查询中国城市或地址的高德地理编码，返回 adcode 与经纬度。")
    public String geocode(
        @ToolParam(description = "城市名或地址，例如：昆明、昆明市五华区。", required = true) String city
    ) {
        long startedAt = System.currentTimeMillis();
        log.info("[AMap-Tool] geocode city={}", city);
        try {
            AmapMapContextService.GeocodeResult result = amapMapContextService.geocode(city);
            log.info("[AMap-Tool] geocode 成功 city={} hasPoint={} adcode={} elapsedMs={}",
                city, result.point() != null && result.point().available(), result.adcode(), elapsed(startedAt));
            return success(AmapToolNames.GEOCODE, result);
        } catch (Exception ex) {
            return failure(AmapToolNames.GEOCODE, ex, startedAt);
        }
    }

    @Tool(name = AmapToolNames.POI_SEARCH, description = "按城市和关键词搜索高德 POI，可用于景点、商圈、交通点等候选查询。")
    public String poiSearch(
        @ToolParam(description = "城市名，例如：昆明。", required = true) String city,
        @ToolParam(description = "搜索关键词，例如：昆明 老人 轻松 景点。", required = true) String keywords,
        @ToolParam(description = "最多返回数量，建议 3 到 8。", required = false) Integer limit
    ) {
        return searchPois(AmapToolNames.POI_SEARCH, city, keywords, limit);
    }

    @Tool(name = AmapToolNames.HOTEL_SEARCH, description = "按城市和住宿偏好搜索高德酒店 POI。")
    public String hotelSearch(
        @ToolParam(description = "城市名，例如：昆明。", required = true) String city,
        @ToolParam(description = "住宿偏好，例如：住得方便一点、舒适型酒店、地铁附近。", required = false) String accommodation,
        @ToolParam(description = "最多返回数量，建议 3 到 8。", required = false) Integer limit
    ) {
        String keywords = city + " " + (isBlank(accommodation) ? "酒店" : accommodation);
        return searchPois(AmapToolNames.HOTEL_SEARCH, city, keywords, limit);
    }

    @Tool(name = AmapToolNames.RESTAURANT_SEARCH, description = "按城市和美食关键词搜索高德餐饮 POI。")
    public String restaurantSearch(
        @ToolParam(description = "城市名，例如：昆明。", required = true) String city,
        @ToolParam(description = "餐饮关键词，例如：特色美食、早餐、米线。", required = false) String keywords,
        @ToolParam(description = "最多返回数量，建议 3 到 8。", required = false) Integer limit
    ) {
        String query = city + " " + (isBlank(keywords) ? "特色美食" : keywords);
        return searchPois(AmapToolNames.RESTAURANT_SEARCH, city, query, limit);
    }

    @Tool(name = AmapToolNames.WEATHER, description = "查询高德天气预报，用于规划每日衣物、户外/室内安排。")
    public String weather(
        @ToolParam(description = "城市名，例如：昆明。", required = true) String city
    ) {
        long startedAt = System.currentTimeMillis();
        log.info("[AMap-Tool] weather city={}", city);
        try {
            AmapMapContextService.GeocodeResult geocode = amapMapContextService.geocode(city);
            List<MapWeatherForecast> forecasts = amapMapContextService.weatherForecasts(city, geocode.adcode());
            log.info("[AMap-Tool] weather 成功 city={} forecastDays={} elapsedMs={}",
                city, forecasts.size(), elapsed(startedAt));
            return success(AmapToolNames.WEATHER, forecasts);
        } catch (Exception ex) {
            return failure(AmapToolNames.WEATHER, ex, startedAt);
        }
    }

    @Tool(name = AmapToolNames.COLLECT_CITY_CONTEXT, description = "一次性采集某城市的高德景点、酒店、餐饮、天气和中心坐标上下文。")
    public String collectCityContext(
        @ToolParam(description = "城市名，例如：昆明。", required = true) String city,
        @ToolParam(description = "该城市停留天数。", required = false) Integer days,
        @ToolParam(description = "用户偏好，多个偏好可用逗号分隔。", required = false) String preferences,
        @ToolParam(description = "住宿偏好。", required = false) String accommodation
    ) {
        long startedAt = System.currentTimeMillis();
        log.info("[AMap-Tool] collectCityContext city={} days={} preferences={} accommodation={}",
            city, days, preferences, accommodation);
        try {
            MapCityRequest request = new MapCityRequest(
                city,
                days == null || days <= 0 ? 1 : days,
                split(preferences),
                accommodation
            );
            MapCityContext context = amapMapContextService.collectCity(request);
            log.info("[AMap-Tool] collectCityContext 成功 city={} attractions={} hotels={} restaurants={} weather={} elapsedMs={}",
                city,
                context.safeAttractions().size(),
                context.safeHotels().size(),
                context.safeRestaurants().size(),
                context.safeWeatherForecasts().size(),
                elapsed(startedAt));
            return success(AmapToolNames.COLLECT_CITY_CONTEXT, context);
        } catch (Exception ex) {
            return failure(AmapToolNames.COLLECT_CITY_CONTEXT, ex, startedAt);
        }
    }

    private String success(String tool, Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(TravelToolResponseFields.SUCCESS, true);
        body.put(TravelToolResponseFields.SOURCE, TravelDataSource.AMAP);
        body.put(TravelToolResponseFields.TOOL, tool);
        body.put(TravelToolResponseFields.DATA, data);
        return JsonUtils.toJsonString(body);
    }

    private String failure(String tool, Exception ex, long startedAt) {
        log.warn("[AMap-Tool] 调用失败 tool={} elapsedMs={} reason={}",
            tool, System.currentTimeMillis() - startedAt, ex.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(TravelToolResponseFields.SUCCESS, false);
        body.put(TravelToolResponseFields.SOURCE, TravelDataSource.AMAP);
        body.put(TravelToolResponseFields.TOOL, tool);
        body.put(TravelToolResponseFields.ERROR, ex.getMessage());
        return JsonUtils.toJsonString(body);
    }

    private String searchPois(String toolName, String city, String keywords, Integer limit) {
        long startedAt = System.currentTimeMillis();
        int safeLimit = safeLimit(limit);
        log.info("[AMap-Tool] {} city={} keywords={} limit={}", toolName, city, keywords, safeLimit);
        try {
            List<MapPoi> pois = amapMapContextService.searchPois(city, keywords, safeLimit);
            log.info("[AMap-Tool] {} 成功 city={} keywords={} resultCount={} elapsedMs={}",
                toolName, city, keywords, pois.size(), elapsed(startedAt));
            return success(toolName, pois);
        } catch (Exception ex) {
            return failure(toolName, ex, startedAt);
        }
    }

    private int safeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return 5;
        }
        return Math.min(limit, 10);
    }

    private List<String> split(String text) {
        if (isBlank(text)) {
            return List.of();
        }
        return Arrays.stream(text.split("[,，、;；\\s]+"))
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .toList();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private long elapsed(long startedAt) {
        return System.currentTimeMillis() - startedAt;
    }
}
