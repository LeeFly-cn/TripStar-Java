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
import java.util.stream.Collectors;

public final class TripPlannerPrompts {

    private TripPlannerPrompts() {
    }

    public static String systemPrompt() {
        return """
            你是 TripStar 的旅行规划智能体，负责把用户的旅行需求规划成结构化 JSON。
            你必须只输出合法 JSON，不要输出 markdown、解释文字或注释。
            JSON key 必须保持英文 snake_case，value 的语言根据用户 language 字段决定。
            你需要兼顾景点顺序、交通便利性、天气、餐饮、酒店、预算和每日节奏。
            如果信息不足，请给出保守可执行的建议，不要输出“无法查询”。
            """;
    }

    public static String plannerPrompt(TripRequest request) {
        return plannerPrompt(
            request,
            MapPlanningContext.empty("none", "未采集地图上下文。"),
            ContentPlanningContext.empty("none", "未采集游记内容上下文。")
        );
    }

    public static String plannerPrompt(TripRequest request, MapPlanningContext mapContext) {
        return plannerPrompt(request, mapContext, ContentPlanningContext.empty("none", "未采集游记内容上下文。"));
    }

    public static String plannerPrompt(
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

        return """
            请为下面的用户生成旅行计划 JSON。

            【基本信息】
            - 途经城市：%s
            - 城市停留：
            %s
            - 开始日期：%s
            - 结束日期：%s
            - 总天数：%s
            - 交通方式：%s
            - 住宿偏好：%s
            - 偏好：%s
            - 额外要求：%s
            - 输出语言：%s

            【地图/POI/天气上下文】
            %s

            【小红书游记上下文】
            %s

            【必须输出的 JSON schema】
            {
              "city": "主城市",
              "cities": ["城市1", "城市2"],
              "start_date": "YYYY-MM-DD",
              "end_date": "YYYY-MM-DD",
              "days": [
                {
                  "date": "YYYY-MM-DD",
                  "day_index": 0,
                  "city": "当天所在城市",
                  "is_transfer_day": false,
                  "transfer_info": "",
                  "description": "当天行程说明",
                  "transportation": "交通方式",
                  "accommodation": "住宿说明",
                  "hotel": {
                    "name": "酒店名",
                    "address": "酒店地址",
                    "location": {"longitude": 116.397, "latitude": 39.908},
                    "price_range": "价格范围",
                    "rating": "评分",
                    "distance": "距离说明",
                    "type": "酒店类型",
                    "estimated_cost": 400
                  },
                  "attractions": [
                    {
                      "name": "景点名",
                      "address": "景点地址",
                      "location": {"longitude": 116.397, "latitude": 39.908},
                      "visit_duration": 120,
                      "description": "景点说明、预约提醒、避坑提示",
                      "category": "景点类型",
                      "rating": 4.7,
                      "image_url": "",
                      "ticket_price": 60
                    }
                  ],
                  "meals": [
                    {
                      "type": "breakfast",
                      "name": "餐饮名称",
                      "address": "地址",
                      "location": {"longitude": 116.397, "latitude": 39.908},
                      "description": "推荐理由",
                      "estimated_cost": 40
                    }
                  ]
                }
              ],
              "weather_info": [
                {
                  "date": "YYYY-MM-DD",
                  "city": "城市",
                  "day_weather": "晴",
                  "night_weather": "多云",
                  "day_temp": 26,
                  "night_temp": 18,
                  "wind_direction": "东南风",
                  "wind_power": "3级"
                }
              ],
              "overall_suggestions": "总体建议",
              "budget": {
                "total_attractions": 300,
                "total_hotels": 1200,
                "total_meals": 600,
                "total_transportation": 500,
                "total_inter_city_transport": 0,
                "total": 2600
              }
            }

            【要求】
            1. days 数组长度必须等于总天数。
            2. 每天安排 2-3 个景点，移动日可以 1-2 个。
            3. 每天必须包含 breakfast、lunch、dinner 三餐。
            4. 每天必须有一个具体 hotel。
            5. location 必须给出合理经纬度，没有精确信息时给城市附近的近似坐标。
            6. 多城市时，每天 city 字段必须正确，切换城市当天设置 is_transfer_day=true。
            7. 只输出 JSON 对象，不要输出 markdown。
            8. 如果地图上下文里有 POI、酒店、餐饮、天气，请优先使用其中的真实名称、地址和经纬度；数量不足时再由你补齐。
            9. 景点顺序要按同城就近、少走回头路的原则安排；移动日行程要轻。
            10. 如果小红书游记上下文里有景点候选、预约提醒、避坑建议，请优先吸收进 attractions.description，并保留 reservation_required / reservation_tips 含义。
            """.formatted(
            cityNames,
            cities,
            safe(request.start_date()),
            safe(request.end_date()),
            request.safeTravelDays(),
            request.safeTransportation(),
            request.safeAccommodation(),
            preferences,
            safe(request.free_text_input()),
            request.safeLanguage(),
            mapContextText,
            contentContextText
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
