package com.zkry.trip.dto;

import java.util.List;

public record TripRequest(
    String city,
    List<CityStay> cities,
    String start_date,
    String end_date,
    Integer travel_days,
    String transportation,
    String accommodation,
    List<String> preferences,
    String free_text_input,
    String language
) {
    public List<CityStay> normalizedCities() {
        if (cities != null && !cities.isEmpty()) {
            return cities;
        }
        String fallbackCity = isBlank(city) ? "北京" : city;
        return List.of(new CityStay(fallbackCity, safeTravelDays()));
    }

    public int safeTravelDays() {
        if (travel_days != null && travel_days > 0) {
            return travel_days;
        }
        if (cities == null || cities.isEmpty()) {
            return 1;
        }
        return cities.stream().mapToInt(CityStay::safeDays).sum();
    }

    public String primaryCity() {
        List<CityStay> normalized = normalizedCities();
        return normalized.isEmpty() ? "" : normalized.getFirst().city();
    }

    public List<String> safePreferences() {
        return preferences == null ? List.of() : preferences;
    }

    public String safeLanguage() {
        return isBlank(language) ? "zh" : language;
    }

    public String safeTransportation() {
        return isBlank(transportation) ? "公共交通" : transportation;
    }

    public String safeAccommodation() {
        return isBlank(accommodation) ? "舒适型酒店" : accommodation;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
