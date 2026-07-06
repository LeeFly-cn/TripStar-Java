package com.zkry.map.dto;

import java.util.List;

public record MapCityContext(
    String city,
    MapPoint center,
    List<MapPoi> attractions,
    List<MapPoi> hotels,
    List<MapPoi> restaurants,
    List<MapWeatherForecast> weatherForecasts
) {
    public List<MapPoi> safeAttractions() {
        return attractions == null ? List.of() : attractions;
    }

    public List<MapPoi> safeHotels() {
        return hotels == null ? List.of() : hotels;
    }

    public List<MapPoi> safeRestaurants() {
        return restaurants == null ? List.of() : restaurants;
    }

    public List<MapWeatherForecast> safeWeatherForecasts() {
        return weatherForecasts == null ? List.of() : weatherForecasts;
    }

    public boolean hasAnyData() {
        return center != null && center.available()
            || !safeAttractions().isEmpty()
            || !safeHotels().isEmpty()
            || !safeRestaurants().isEmpty()
            || !safeWeatherForecasts().isEmpty();
    }
}
