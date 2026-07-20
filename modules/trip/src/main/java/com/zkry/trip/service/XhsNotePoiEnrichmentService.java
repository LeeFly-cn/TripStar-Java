package com.zkry.trip.service;

import com.zkry.common.core.constant.TravelDataSource;
import com.zkry.common.core.exception.BizException;
import com.zkry.common.core.exception.CommonErrorCode;
import com.zkry.map.dto.MapCityContext;
import com.zkry.map.dto.MapPlanningContext;
import com.zkry.map.dto.MapPoi;
import com.zkry.map.dto.MapPoint;
import com.zkry.map.service.AmapMapContextService;
import com.zkry.trip.dto.TripRequest;
import com.zkry.trip.dto.xhsnote.XhsNotePlace;
import com.zkry.trip.dto.xhsnote.XhsNoteUnderstandingResult;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 通过高德 Service 补全指定笔记中的地点信息。
 *
 * <p>高德搜索已经使用城市限制，并按相关度返回结果。本服务每个地点只请求一条 POI，
 * 直接采用第一条结果，不再调用大模型，也不在 Java 中重复实现一套相似度判断。
 */
@Service
public class XhsNotePoiEnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(XhsNotePoiEnrichmentService.class);
    private static final int POI_RESULT_LIMIT = 1;

    private final AmapMapContextService amapMapContextService;

    public XhsNotePoiEnrichmentService(AmapMapContextService amapMapContextService) {
        this.amapMapContextService = amapMapContextService;
    }

    /** 补全城市中心点，以及笔记中明确出现的景点、酒店和餐饮 POI。 */
    public MapPlanningContext enrich(
        String taskId,
        TripRequest trip,
        XhsNoteUnderstandingResult understanding
    ) {
        amapMapContextService.validateReady();

        Map<String, CityPoiAccumulator> cities = new LinkedHashMap<>();
        trip.normalizedCities().forEach(cityStay -> {
            String city = requireText(cityStay.city(), "行程城市不能为空");
            cities.put(city, new CityPoiAccumulator(city));
        });
        if (cities.isEmpty()) {
            throw new BizException("指定笔记没有可用于高德 POI 查询的城市。");
        }

        // 每个城市只查询一次中心坐标，供地图展示和后续酒店餐饮 Agent 使用。
        cities.values().forEach(city -> city.center = geocode(taskId, city.city));

        for (XhsNotePlace place : understanding.safeAttractions()) {
            CityPoiAccumulator city = city(cities, place, "景点");
            city.attractions.add(firstPoi(taskId, city.city, place.name(), "景点"));
        }
        for (XhsNotePlace place : understanding.safeHotels()) {
            CityPoiAccumulator city = city(cities, place, "酒店");
            city.hotels.add(firstPoi(taskId, city.city, place.name(), "酒店"));
        }
        for (XhsNotePlace place : understanding.safeRestaurants()) {
            CityPoiAccumulator city = city(cities, place, "餐饮");
            city.restaurants.add(firstPoi(taskId, city.city, place.name(), "餐饮"));
        }

        int attractionCount = cities.values().stream().mapToInt(city -> city.attractions.size()).sum();
        int hotelCount = cities.values().stream().mapToInt(city -> city.hotels.size()).sum();
        int restaurantCount = cities.values().stream().mapToInt(city -> city.restaurants.size()).sum();
        if (attractionCount + hotelCount + restaurantCount == 0) {
            throw new BizException("指定笔记没有需要补全的高德 POI 地点。");
        }

        List<MapCityContext> cityContexts = cities.values().stream()
            .map(CityPoiAccumulator::toContext)
            .toList();
        String message = "指定笔记 POI Service 补全完成：景点=" + attractionCount
            + "，酒店=" + hotelCount
            + "，餐饮=" + restaurantCount;
        log.info("[XHS-NOTE-POI] 补全完成 taskId={} cities={} attractions={} hotels={} restaurants={}",
            taskId, cityContexts.size(), attractionCount, hotelCount, restaurantCount);
        return new MapPlanningContext(cityContexts, true, TravelDataSource.AMAP, message);
    }

    /** 根据笔记地点所属城市找到对应的结果容器，不允许跨城市写入。 */
    private CityPoiAccumulator city(
        Map<String, CityPoiAccumulator> cities,
        XhsNotePlace place,
        String category
    ) {
        if (place == null) {
            throw new BizException(category + "信息不能为空。");
        }
        String name = requireText(place.name(), category + "名称不能为空");
        String cityName = requireText(place.city(), name + " 缺少所属城市");
        CityPoiAccumulator city = cities.get(cityName);
        if (city == null) {
            throw new BizException("地点所属城市不在行程城市中：" + name + " -> " + cityName);
        }
        return city;
    }

    /** 每个地点只取高德按相关度返回的第一条结果。 */
    private MapPoi firstPoi(String taskId, String city, String name, String category) {
        String expectedName = requireText(name, category + "名称不能为空");
        List<MapPoi> pois = searchPois(city, expectedName);
        if (pois.isEmpty()) {
            throw new BizException("高德未找到" + category + " POI：" + city + " / " + expectedName);
        }
        MapPoi poi = pois.getFirst();
        if (poi.location() == null || !poi.location().available()) {
            throw new BizException("高德 POI 缺少经纬度：" + city + " / " + expectedName);
        }
        log.info("[XHS-NOTE-POI] 补全成功 taskId={} category={} city={} expected={} official={} address={} location={},{}",
            taskId,
            category,
            city,
            expectedName,
            poi.name(),
            poi.address(),
            poi.location().longitude(),
            poi.location().latitude());
        return poi;
    }

    private MapPoint geocode(String taskId, String city) {
        try {
            AmapMapContextService.GeocodeResult result = amapMapContextService.geocode(city);
            if (result.point() == null || !result.point().available()) {
                throw new BizException("高德没有返回城市中心坐标：" + city);
            }
            log.info("[XHS-NOTE-POI] 城市定位成功 taskId={} city={} adcode={} location={},{}",
                taskId, city, result.adcode(), result.point().longitude(), result.point().latitude());
            return result.point();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BizException(CommonErrorCode.BUSINESS_ERROR, "高德城市定位被中断：" + city, ex);
        } catch (IOException ex) {
            throw new BizException(CommonErrorCode.BUSINESS_ERROR, "高德城市定位失败：" + city, ex);
        }
    }

    private List<MapPoi> searchPois(String city, String name) {
        try {
            return amapMapContextService.searchPois(city, name, POI_RESULT_LIMIT);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BizException(CommonErrorCode.BUSINESS_ERROR,
                "高德 POI 查询被中断：" + city + " / " + name, ex);
        } catch (IOException ex) {
            throw new BizException(CommonErrorCode.BUSINESS_ERROR,
                "高德 POI 查询失败：" + city + " / " + name, ex);
        }
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BizException(message);
        }
        return value.trim();
    }

    /** 单城市 POI 累加器，完成后转换为 Planner 使用的不可变地图上下文。 */
    private static final class CityPoiAccumulator {

        private final String city;
        private MapPoint center;
        private final List<MapPoi> attractions = new ArrayList<>();
        private final List<MapPoi> hotels = new ArrayList<>();
        private final List<MapPoi> restaurants = new ArrayList<>();

        private CityPoiAccumulator(String city) {
            this.city = city;
        }

        private MapCityContext toContext() {
            return new MapCityContext(
                city,
                center,
                List.copyOf(attractions),
                List.copyOf(hotels),
                List.copyOf(restaurants),
                List.of()
            );
        }
    }
}
