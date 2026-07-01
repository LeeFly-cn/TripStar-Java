package com.zkry.trip.dto;

public record CityStay(
    String city,
    Integer days
) {
    public int safeDays() {
        return days == null || days < 1 ? 1 : days;
    }
}
