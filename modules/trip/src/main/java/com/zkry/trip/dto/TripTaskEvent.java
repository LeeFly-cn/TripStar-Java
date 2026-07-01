package com.zkry.trip.dto;

import java.util.Map;

public record TripTaskEvent(
    String task_id,
    String plan_id,
    String status,
    String stage,
    Integer progress,
    String message,
    String error,
    TripPlanResponse result,
    Map<String, Object> request_payload
) {
}
