package com.zkry.trip.dto;

import java.util.List;

public record DayPlan(
    String date,
    Integer day_index,
    String city,
    Boolean is_transfer_day,
    String transfer_info,
    String description,
    String transportation,
    String accommodation,
    Hotel hotel,
    List<Attraction> attractions,
    List<Meal> meals
) {
}
