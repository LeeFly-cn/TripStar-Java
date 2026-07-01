package com.zkry.trip.dto;

import java.util.List;

public record TripPlan(
    String city,
    List<String> cities,
    String start_date,
    String end_date,
    List<DayPlan> days,
    List<WeatherInfo> weather_info,
    String overall_suggestions,
    Budget budget
) {
}
