package com.zkry.trip.dto;

public record Meal(
    String type,
    String name,
    String address,
    Location location,
    String description,
    Integer estimated_cost
) {
}
