package com.zkry.trip.dto;

public record Hotel(
    String name,
    String address,
    Location location,
    String price_range,
    String rating,
    String distance,
    String type,
    Integer estimated_cost
) {
}
