package com.zkry.trip.dto;

public record Attraction(
    String name,
    String address,
    Location location,
    Integer visit_duration,
    String description,
    String category,
    Double rating,
    String image_url,
    Integer ticket_price
) {
}
