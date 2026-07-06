package com.zkry.trip.dto;

public record SubmitTripPlanResponse(
    String task_id,
    String plan_id,
    String status,
    String ws_url,
    String message
) {
}
