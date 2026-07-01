package com.zkry.trip.dto;

public record TripPlanResponse(
    Boolean success,
    String message,
    String plan_id,
    TripPlan data,
    KnowledgeGraphData graph_data
) {
}
