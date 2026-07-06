package com.zkry.trip.service;

import com.zkry.trip.dto.Attraction;
import com.zkry.trip.dto.DayPlan;
import com.zkry.trip.dto.GraphCategory;
import com.zkry.trip.dto.GraphEdge;
import com.zkry.trip.dto.GraphNode;
import com.zkry.trip.dto.KnowledgeGraphData;
import com.zkry.trip.dto.Meal;
import com.zkry.trip.dto.TripPlan;
import com.zkry.trip.dto.TripPlanResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 PlannerAgent 返回的 {@link TripPlan} 包装成前端需要的响应结构。
 *
 * <p>前端图谱不是额外查询来的知识库数据，而是从行程 JSON 派生出来的展示图：
 * 城市连接每天，每天再连接酒店、景点、餐饮和预算节点。这样前端能直接复用原 Vue
 * 项目的知识图谱组件。
 */
final class TripPlanResponseFactory {

    private TripPlanResponseFactory() {
    }

    static TripPlanResponse fromPlan(String planId, TripPlan plan) {
        return new TripPlanResponse(
            true,
            "旅行计划生成成功",
            planId,
            plan,
            createKnowledgeGraph(plan)
        );
    }

    /**
     * 生成前端 ECharts/Graph 可用的节点和边。
     */
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
        String cityName = plan.city() == null || plan.city().isBlank() ? "行程" : plan.city();
        String rootId = "city_" + cityName;
        nodes.add(node(
            rootId,
            plan.cities() == null || plan.cities().isEmpty() ? cityName : String.join(" → ", plan.cities()),
            0,
            52,
            "#64b5f6",
            safe(plan.start_date()) + " ~ " + safe(plan.end_date())
        ));

        List<DayPlan> days = plan.days() == null ? List.of() : plan.days();
        for (DayPlan day : days) {
            String dayId = "day_" + day.day_index();
            nodes.add(node(dayId, "第" + (day.day_index() + 1) + "天", 1, 42, "#81c784", safe(day.date())));
            edges.add(new GraphEdge(rootId, dayId, "行程"));

            if (day.hotel() != null) {
                String hotelId = "hotel_" + day.day_index();
                nodes.add(node(hotelId, day.hotel().name(), 3, 34, "#ffb74d", day.hotel().price_range()));
                edges.add(new GraphEdge(dayId, hotelId, "入住"));
            }
            List<Attraction> attractions = day.attractions() == null ? List.of() : day.attractions();
            for (int i = 0; i < attractions.size(); i++) {
                Attraction attraction = attractions.get(i);
                String attrId = "attr_" + day.day_index() + "_" + i;
                nodes.add(node(attrId, attraction.name(), 2, 36, "#f06292", attraction.description()));
                edges.add(new GraphEdge(dayId, attrId, "游览"));
            }
            List<Meal> meals = day.meals() == null ? List.of() : day.meals();
            for (int i = 0; i < meals.size(); i++) {
                Meal meal = meals.get(i);
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
        return new GraphNode(id, safe(name), category, size, style, safe(value));
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
