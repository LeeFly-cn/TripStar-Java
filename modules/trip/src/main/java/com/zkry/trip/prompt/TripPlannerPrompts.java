package com.zkry.trip.prompt;

import com.zkry.content.dto.ContentAttractionCandidate;
import com.zkry.content.dto.ContentCityContext;
import com.zkry.content.dto.ContentPlanningContext;
import com.zkry.map.dto.MapCityContext;
import com.zkry.map.dto.MapPlanningContext;
import com.zkry.map.dto.MapPoi;
import com.zkry.map.dto.MapWeatherForecast;
import com.zkry.trip.dto.CityStay;
import com.zkry.trip.dto.TripRequest;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class TripPlannerPrompts {

    public static final String PLANNER_SYSTEM = "prompts/tripstar/planner-system.md";
    public static final String PLANNER_USER = "prompts/tripstar/planner-user.md";
    public static final String REVIEW_SYSTEM = "prompts/tripstar/review-system.md";
    public static final String REVIEW_USER = "prompts/tripstar/review-user.md";
    public static final String JSON_REPAIR_SYSTEM = "prompts/tripstar/json-repair-system.md";
    public static final String JSON_REPAIR_USER = "prompts/tripstar/json-repair-user.md";

    private TripPlannerPrompts() {
    }

    public static Map<String, String> plannerVariables(
        TripRequest request,
        MapPlanningContext mapContext,
        ContentPlanningContext contentContext
    ) {
        String cities = request.normalizedCities().stream()
            .map(city -> "- " + city.city() + ": " + city.safeDays() + "天")
            .collect(Collectors.joining("\n"));
        String preferences = request.safePreferences().isEmpty()
            ? "无"
            : String.join("、", request.safePreferences());
        String cityNames = request.normalizedCities().stream()
            .map(CityStay::city)
            .collect(Collectors.joining("、"));
        String mapContextText = mapContextBlock(mapContext);
        String contentContextText = contentContextBlock(contentContext);

        return Map.ofEntries(
            Map.entry("city_names", cityNames),
            Map.entry("city_stays", cities),
            Map.entry("start_date", safe(request.start_date())),
            Map.entry("end_date", safe(request.end_date())),
            Map.entry("travel_days", String.valueOf(request.safeTravelDays())),
            Map.entry("transportation", request.safeTransportation()),
            Map.entry("accommodation", request.safeAccommodation()),
            Map.entry("preferences", preferences),
            Map.entry("free_text_input", safe(request.free_text_input())),
            Map.entry("language", request.safeLanguage()),
            Map.entry("map_context", mapContextText),
            Map.entry("content_context", contentContextText)
        );
    }

    private static String mapContextBlock(MapPlanningContext context) {
        if (context == null || !context.realData() || context.safeCities().isEmpty()) {
            String message = context == null ? "没有地图上下文。" : context.message();
            return "- 数据源：" + safe(context == null ? "" : context.source()) + "\n- 状态：" + safe(message);
        }
        return context.safeCities().stream()
            .map(TripPlannerPrompts::cityContextBlock)
            .collect(Collectors.joining("\n\n"));
    }

    private static String cityContextBlock(MapCityContext context) {
        return """
            城市：%s
            - 景点候选：%s
            - 酒店候选：%s
            - 餐饮候选：%s
            - 天气预报：%s
            """.formatted(
            context.city(),
            poiLines(context.safeAttractions()),
            poiLines(context.safeHotels()),
            poiLines(context.safeRestaurants()),
            weatherLines(context.safeWeatherForecasts())
        );
    }

    private static String poiLines(List<MapPoi> pois) {
        if (pois == null || pois.isEmpty()) {
            return "无";
        }
        return pois.stream()
            .limit(5)
            .map(poi -> "%s（%s，%s，评分%s，经纬度%s）".formatted(
                safe(poi.name()),
                safe(poi.address()),
                safe(poi.type()),
                safe(poi.rating()),
                poi.location() == null ? "未知" : poi.location().longitude() + "," + poi.location().latitude()
            ))
            .collect(Collectors.joining("；"));
    }

    private static String weatherLines(List<MapWeatherForecast> forecasts) {
        if (forecasts == null || forecasts.isEmpty()) {
            return "无";
        }
        return forecasts.stream()
            .limit(5)
            .map(weather -> "%s 白天%s%s℃ 夜间%s%s℃ %s%s".formatted(
                safe(weather.date()),
                safe(weather.dayWeather()),
                weather.dayTemp() == null ? "" : weather.dayTemp(),
                safe(weather.nightWeather()),
                weather.nightTemp() == null ? "" : weather.nightTemp(),
                safe(weather.windDirection()),
                safe(weather.windPower())
            ))
            .collect(Collectors.joining("；"));
    }

    private static String contentContextBlock(ContentPlanningContext context) {
        if (context == null || !context.realData() || context.safeCities().isEmpty()) {
            String message = context == null ? "没有游记内容上下文。" : context.message();
            return "- 数据源：" + safe(context == null ? "" : context.source()) + "\n- 状态：" + safe(message);
        }
        return context.safeCities().stream()
            .map(TripPlannerPrompts::contentCityBlock)
            .collect(Collectors.joining("\n\n"));
    }

    private static String contentCityBlock(ContentCityContext context) {
        String candidates = contentCandidateLines(context.safeAttractions());
        String raw = context.rawText() == null || context.rawText().isBlank()
            ? "无"
            : truncate(context.rawText(), 2200);
        return """
            城市：%s
            - 搜索关键词：%s
            - 提炼景点候选：%s
            - 游记原文摘要：
            %s
            """.formatted(
            context.city(),
            safe(context.keyword()),
            candidates,
            raw
        );
    }

    private static String contentCandidateLines(List<ContentAttractionCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return "无";
        }
        return candidates.stream()
            .limit(8)
            .map(item -> "%s（中文名%s，英文名%s，建议%s分钟，预约%s，提示：%s，理由：%s）".formatted(
                safe(item.name()),
                safe(item.name_zh()),
                safe(item.name_en()),
                item.duration() == null ? "未知" : item.duration(),
                Boolean.TRUE.equals(item.reservation_required()) ? "需要" : "不需要或未提及",
                safe(item.reservation_tips()),
                safe(item.reason())
            ))
            .collect(Collectors.joining("；"));
    }

    private static String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "\n...(已截断)";
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "未填写" : value;
    }
}
