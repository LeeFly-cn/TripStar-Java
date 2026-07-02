package com.zkry.map.dto;

import java.util.List;
import java.util.Optional;

public record MapPlanningContext(
    List<MapCityContext> cities,
    boolean realData,
    String source,
    String message
) {
    public static MapPlanningContext empty(String source, String message) {
        return new MapPlanningContext(List.of(), false, source, message);
    }

    public List<MapCityContext> safeCities() {
        return cities == null ? List.of() : cities;
    }

    public Optional<MapCityContext> findCity(String city) {
        if (city == null || city.isBlank()) {
            return Optional.empty();
        }
        return safeCities().stream()
            .filter(context -> city.equals(context.city()))
            .findFirst();
    }
}
