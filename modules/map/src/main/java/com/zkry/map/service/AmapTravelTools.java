package com.zkry.map.service;

import com.zkry.common.core.constant.TravelDataSource;
import com.zkry.common.core.constant.TravelToolResponseFields;
import com.zkry.common.json.utils.JsonUtils;
import com.zkry.map.dto.MapPoi;
import com.zkry.map.dto.MapWeatherForecast;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 高德地图工具底层实现。
 *
 * <p>方法返回 JSON 字符串而不是 Java 对象，是为了让 LLM 看到稳定、可读的
 * {@code success/source/tool/data/error} 结构。真正的 HTTP 调用仍放在
 * {@link AmapMapContextService}；真正暴露给 ReactAgent 的白名单在
 * {@link AmapGeoPoiTools}、{@link AmapWeatherTools} 和 {@link AmapHotelTools}。
 */
@Component
public class AmapTravelTools {

    private static final Logger log = LoggerFactory.getLogger(AmapTravelTools.class);

    private final AmapMapContextService amapMapContextService;

    public AmapTravelTools(AmapMapContextService amapMapContextService) {
        this.amapMapContextService = amapMapContextService;
    }

    public String geocode(String city) {
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

    public String poiSearch(String city, String keywords, Integer limit) {
        return searchPois(AmapToolNames.POI_SEARCH, city, keywords, limit);
    }

    public String hotelSearch(String city, String accommodation, Integer limit) {
        String keywords = city + " " + (isBlank(accommodation) ? "酒店" : accommodation);
        return searchPois(AmapToolNames.HOTEL_SEARCH, city, keywords, limit);
    }

    public String restaurantSearch(String city, String keywords, Integer limit) {
        String query = city + " " + (isBlank(keywords) ? "特色美食" : keywords);
        return searchPois(AmapToolNames.RESTAURANT_SEARCH, city, query, limit);
    }

    public String weather(String city) {
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

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private long elapsed(long startedAt) {
        return System.currentTimeMillis() - startedAt;
    }
}
