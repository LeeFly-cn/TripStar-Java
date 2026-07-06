package com.zkry.trip.dto;

public record Budget(
    Integer total_attractions,
    Integer total_hotels,
    Integer total_meals,
    Integer total_transportation,
    Integer total_inter_city_transport,
    Integer total
) {
}
