package com.zkry.content.dto;

import java.util.List;
import java.util.Optional;

public record ContentPlanningContext(
    List<ContentCityContext> cities,
    boolean realData,
    String source,
    String message
) {
    public static ContentPlanningContext empty(String source, String message) {
        return new ContentPlanningContext(List.of(), false, source, message);
    }

    public List<ContentCityContext> safeCities() {
        return cities == null ? List.of() : cities;
    }

    public Optional<ContentCityContext> findCity(String city) {
        if (city == null || city.isBlank()) {
            return Optional.empty();
        }
        return safeCities().stream()
            .filter(context -> city.equals(context.city()))
            .findFirst();
    }
}
