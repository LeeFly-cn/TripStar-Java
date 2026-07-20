package com.zkry.map.service;

import com.zkry.common.core.constant.TravelDataSource;
import com.zkry.common.core.constant.TravelToolResponseFields;
import com.zkry.common.json.utils.JsonUtils;
import com.zkry.map.dto.MapPoi;
import com.zkry.map.dto.MapWeatherForecast;
import java.util.ArrayList;
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
    private static final int MAX_BATCH_QUERIES = 20;
    private static final String KEYWORDS_FIELD = "keywords";
    private static final String AMAP_RESTAURANT_TYPE = "050000";
    private static final String AMAP_HOTEL_TYPE = "100000";

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
        return searchPois(AmapToolNames.HOTEL_SEARCH, city, keywords, AMAP_HOTEL_TYPE, limit);
    }

    public String restaurantSearch(String city, String keywords, Integer limit) {
        String query = city + " " + (isBlank(keywords) ? "特色美食" : keywords);
        return searchPois(AmapToolNames.RESTAURANT_SEARCH, city, query, AMAP_RESTAURANT_TYPE, limit);
    }

    /**
     * 批量执行同一城市的餐饮查询。
     *
     * <p>Tool 只减少 Agent 的 function call 次数，不合并或改写模型生成的关键词。
     * 任意一次高德请求异常都会让整个 Tool 返回失败，调用方可以看到真实错误。
     */
    public String restaurantBatchSearch(String city, List<String> keywords, Integer limitPerKeyword) {
        long startedAt = System.currentTimeMillis();
        int safeLimit = safeLimit(limitPerKeyword);
        List<String> queries = keywords == null
            ? List.of()
            : keywords.stream()
                .filter(keyword -> !isBlank(keyword))
                .map(String::trim)
                .distinct()
                .toList();
        log.info("[AMap-Tool] {} 开始 city={} queryCount={} limitPerKeyword={}",
            AmapToolNames.RESTAURANT_BATCH_SEARCH, city, queries.size(), safeLimit);
        if (queries.isEmpty()) {
            return failure(
                AmapToolNames.RESTAURANT_BATCH_SEARCH,
                new IllegalArgumentException("餐饮批量搜索关键词不能为空"),
                startedAt
            );
        }
        if (queries.size() > MAX_BATCH_QUERIES) {
            return failure(
                AmapToolNames.RESTAURANT_BATCH_SEARCH,
                new IllegalArgumentException("餐饮批量搜索单次最多支持 " + MAX_BATCH_QUERIES + " 组关键词，请分批调用"),
                startedAt
            );
        }

        try {
            List<Map<String, Object>> groupedPois = new ArrayList<>();
            int totalPois = 0;
            for (int index = 0; index < queries.size(); index++) {
                String keywordsForMeal = queries.get(index);
                String query = city + " " + keywordsForMeal;
                long queryStartedAt = System.currentTimeMillis();
                log.info("[AMap-Tool] {} 查询 city={} index={}/{} keywords={}",
                    AmapToolNames.RESTAURANT_BATCH_SEARCH,
                    city,
                    index + 1,
                    queries.size(),
                    keywordsForMeal);
                List<MapPoi> pois = amapMapContextService.searchPois(
                    city,
                    query,
                    AMAP_RESTAURANT_TYPE,
                    safeLimit
                );
                totalPois += pois.size();

                Map<String, Object> grouped = new LinkedHashMap<>();
                grouped.put(KEYWORDS_FIELD, keywordsForMeal);
                grouped.put(TravelToolResponseFields.DATA, pois);
                groupedPois.add(grouped);
                log.info("[AMap-Tool] {} 单项成功 city={} index={}/{} resultCount={} elapsedMs={}",
                    AmapToolNames.RESTAURANT_BATCH_SEARCH,
                    city,
                    index + 1,
                    queries.size(),
                    pois.size(),
                    elapsed(queryStartedAt));
            }
            log.info("[AMap-Tool] {} 完成 city={} queryCount={} totalPois={} elapsedMs={}",
                AmapToolNames.RESTAURANT_BATCH_SEARCH,
                city,
                queries.size(),
                totalPois,
                elapsed(startedAt));
            return success(AmapToolNames.RESTAURANT_BATCH_SEARCH, groupedPois);
        } catch (Exception ex) {
            return failure(AmapToolNames.RESTAURANT_BATCH_SEARCH, ex, startedAt);
        }
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
        return searchPois(toolName, city, keywords, "", limit);
    }

    private String searchPois(
        String toolName,
        String city,
        String keywords,
        String types,
        Integer limit
    ) {
        long startedAt = System.currentTimeMillis();
        int safeLimit = safeLimit(limit);
        log.info("[AMap-Tool] {} city={} keywords={} types={} limit={}",
            toolName, city, keywords, isBlank(types) ? "-" : types, safeLimit);
        try {
            List<MapPoi> pois = amapMapContextService.searchPois(city, keywords, types, safeLimit);
            log.info("[AMap-Tool] {} 成功 city={} keywords={} types={} resultCount={} elapsedMs={}",
                toolName, city, keywords, isBlank(types) ? "-" : types, pois.size(), elapsed(startedAt));
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
