package com.zkry.trip.dto;

public record TripHistoryItem(
    String plan_id,
    String task_id,
    String city,
    String start_date,
    String end_date,
    Integer travel_days,
    String updated_at,
    String overall_suggestions
) {
}
