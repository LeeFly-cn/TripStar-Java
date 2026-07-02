package com.zkry.trip.service;

import com.zkry.trip.dto.Attraction;
import com.zkry.trip.dto.Budget;
import com.zkry.trip.dto.CityStay;
import com.zkry.trip.dto.DayPlan;
import com.zkry.trip.dto.GraphCategory;
import com.zkry.trip.dto.GraphEdge;
import com.zkry.trip.dto.GraphNode;
import com.zkry.trip.dto.Hotel;
import com.zkry.trip.dto.KnowledgeGraphData;
import com.zkry.trip.dto.Location;
import com.zkry.trip.dto.Meal;
import com.zkry.trip.dto.TripPlan;
import com.zkry.trip.dto.TripPlanResponse;
import com.zkry.trip.dto.TripRequest;
import com.zkry.trip.dto.WeatherInfo;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class TripMockPlanFactory {

    private TripMockPlanFactory() {
    }

    static TripPlanResponse create(String planId, TripRequest request) {
        TripPlan plan = createPlan(request);
        return new TripPlanResponse(
            true,
            "旅行计划生成成功",
            planId,
            plan,
            createKnowledgeGraph(plan)
        );
    }

    private static TripPlan createPlan(TripRequest request) {
        List<CityStay> cityStays = request.normalizedCities();
        List<String> cityNames = cityStays.stream().map(CityStay::city).toList();
        int totalDays = Math.max(1, request.safeTravelDays());
        LocalDate startDate = parseDate(request.start_date());

        List<DayPlan> days = new ArrayList<>();
        List<WeatherInfo> weather = new ArrayList<>();
        int dayIndex = 0;
        String previousCity = "";
        for (CityStay cityStay : cityStays) {
            for (int i = 0; i < cityStay.safeDays() && dayIndex < totalDays; i++) {
                String city = cityStay.city();
                boolean transferDay = !previousCity.isBlank() && !previousCity.equals(city) && i == 0;
                LocalDate date = startDate.plusDays(dayIndex);
                days.add(createDay(date, dayIndex, city, transferDay, previousCity, request));
                weather.add(createWeather(date, city, dayIndex));
                previousCity = city;
                dayIndex++;
            }
        }
        while (dayIndex < totalDays) {
            String city = cityNames.isEmpty() ? request.primaryCity() : cityNames.getLast();
            LocalDate date = startDate.plusDays(dayIndex);
            days.add(createDay(date, dayIndex, city, false, "", request));
            weather.add(createWeather(date, city, dayIndex));
            dayIndex++;
        }

        int hotelCost = totalDays * 420;
        int mealCost = totalDays * 180;
        int attractionCost = totalDays * 160;
        int transportCost = totalDays * 90;
        int interCityCost = cityNames.size() > 1 ? (cityNames.size() - 1) * 260 : 0;
        Budget budget = new Budget(
            attractionCost,
            hotelCost,
            mealCost,
            transportCost,
            interCityCost,
            attractionCost + hotelCost + mealCost + transportCost + interCityCost
        );

        String suggestion = "这是 Java 后端 mock 生成的学习版行程。后续会替换为 Spring AI Alibaba 生成结果；当前结构已兼容 Vue 展示。";
        if (!request.safePreferences().isEmpty()) {
            suggestion += " 已记录你的偏好：" + String.join("、", request.safePreferences()) + "。";
        }
        if (request.free_text_input() != null && !request.free_text_input().isBlank()) {
            suggestion += " 额外要求：" + request.free_text_input();
        }

        return new TripPlan(
            request.primaryCity(),
            cityNames,
            startDate.toString(),
            request.end_date() == null || request.end_date().isBlank()
                ? startDate.plusDays(totalDays - 1L).toString()
                : request.end_date(),
            days,
            weather,
            suggestion,
            budget
        );
    }

    private static DayPlan createDay(
        LocalDate date,
        int dayIndex,
        String city,
        boolean transferDay,
        String previousCity,
        TripRequest request
    ) {
        Location base = pseudoLocation(city, dayIndex);
        List<Attraction> attractions = List.of(
            new Attraction(
                city + "城市地标",
                city + "中心城区",
                base,
                transferDay ? 90 : 150,
                "结合用户偏好生成的代表性城市地标，后续会由 POI 和 LLM 提炼结果替换。",
                "地标",
                4.7,
                "",
                60
            ),
            new Attraction(
                city + "特色街区",
                city + "老城片区",
                new Location(base.longitude() + 0.018, base.latitude() + 0.012),
                120,
                "适合体验当地美食、街巷和生活气息的区域。",
                "街区",
                4.6,
                "",
                0
            )
        );
        Hotel hotel = new Hotel(
            city + request.safeAccommodation(),
            city + "核心商圈附近",
            new Location(base.longitude() + 0.006, base.latitude() - 0.009),
            "350-520元/晚",
            "4.6",
            "距主要景点约2公里",
            request.safeAccommodation(),
            420
        );
        List<Meal> meals = List.of(
            new Meal("breakfast", city + "本地早餐", "", null, "选择靠近酒店的本地早餐店，减少早晨交通成本。", 30),
            new Meal("lunch", city + "特色午餐", "", null, "安排在上午景点附近，避免折返。", 70),
            new Meal("dinner", city + "风味晚餐", "", null, "晚餐靠近住宿区，方便休息。", 80)
        );
        String transferInfo = transferDay ? previousCity + " → " + city + "，建议高铁或城际交通，抵达后安排轻量游览。" : "";
        return new DayPlan(
            date.toString(),
            dayIndex,
            city,
            transferDay,
            transferInfo,
            "第" + (dayIndex + 1) + "天以" + city + "城市体验为主，交通方式：" + request.safeTransportation() + "。",
            request.safeTransportation(),
            request.safeAccommodation(),
            hotel,
            attractions,
            meals
        );
    }

    private static WeatherInfo createWeather(LocalDate date, String city, int dayIndex) {
        return new WeatherInfo(
            date.toString(),
            city,
            dayIndex % 3 == 0 ? "晴" : "多云",
            "多云",
            24 + dayIndex % 5,
            16 + dayIndex % 4,
            "东南风",
            "3级"
        );
    }

    private static KnowledgeGraphData createKnowledgeGraph(TripPlan plan) {
        List<GraphCategory> categories = List.of(
            new GraphCategory("城市"),
            new GraphCategory("天数"),
            new GraphCategory("景点"),
            new GraphCategory("酒店"),
            new GraphCategory("餐饮"),
            new GraphCategory("天气"),
            new GraphCategory("预算"),
            new GraphCategory("建议")
        );
        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        String rootId = "city_" + plan.city();
        nodes.add(node(rootId, plan.cities() == null || plan.cities().isEmpty() ? plan.city() : String.join(" → ", plan.cities()), 0, 52, "#64b5f6", plan.start_date() + " ~ " + plan.end_date()));

        for (DayPlan day : plan.days()) {
            String dayId = "day_" + day.day_index();
            nodes.add(node(dayId, "第" + (day.day_index() + 1) + "天", 1, 42, "#81c784", day.date()));
            edges.add(new GraphEdge(rootId, dayId, "行程"));

            if (day.hotel() != null) {
                String hotelId = "hotel_" + day.day_index();
                nodes.add(node(hotelId, day.hotel().name(), 3, 34, "#ffb74d", day.hotel().price_range()));
                edges.add(new GraphEdge(dayId, hotelId, "入住"));
            }
            for (int i = 0; i < day.attractions().size(); i++) {
                Attraction attraction = day.attractions().get(i);
                String attrId = "attr_" + day.day_index() + "_" + i;
                nodes.add(node(attrId, attraction.name(), 2, 36, "#f06292", attraction.description()));
                edges.add(new GraphEdge(dayId, attrId, "游览"));
            }
            for (int i = 0; i < day.meals().size(); i++) {
                Meal meal = day.meals().get(i);
                String mealId = "meal_" + day.day_index() + "_" + i;
                nodes.add(node(mealId, meal.name(), 4, 28, "#ba68c8", "¥" + meal.estimated_cost()));
                edges.add(new GraphEdge(dayId, mealId, meal.type()));
            }
        }
        if (plan.budget() != null) {
            nodes.add(node("budget_total", "总预算 ¥" + plan.budget().total(), 6, 40, "#4db6ac", ""));
            edges.add(new GraphEdge(rootId, "budget_total", "预算"));
        }
        return new KnowledgeGraphData(nodes, edges, categories);
    }

    private static GraphNode node(String id, String name, int category, int size, String color, String value) {
        Map<String, String> style = new LinkedHashMap<>();
        style.put("color", color);
        return new GraphNode(id, name, category, size, style, value);
    }

    private static Location pseudoLocation(String city, int dayIndex) {
        int hash = Math.abs(city == null ? 0 : city.hashCode());
        double longitude = 100.0 + (hash % 2200) / 100.0 + dayIndex * 0.01;
        double latitude = 20.0 + (hash % 1200) / 100.0 + dayIndex * 0.01;
        return new Location(round(longitude), round(latitude));
    }

    private static double round(double value) {
        return Math.round(value * 1000000.0) / 1000000.0;
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return LocalDate.now();
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException ignored) {
            return LocalDate.now();
        }
    }
}
