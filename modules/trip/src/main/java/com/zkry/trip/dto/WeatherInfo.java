package com.zkry.trip.dto;

public record WeatherInfo(
    String date,
    String city,
    String day_weather,
    String night_weather,
    Integer day_temp,
    Integer night_temp,
    String wind_direction,
    String wind_power
) {
}
