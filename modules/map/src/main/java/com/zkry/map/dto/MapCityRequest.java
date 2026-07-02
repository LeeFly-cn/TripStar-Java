package com.zkry.map.dto;

import java.util.List;

public record MapCityRequest(
    String city,
    int days,
    List<String> preferences,
    String accommodation
) {
    public List<String> safePreferences() {
        return preferences == null ? List.of() : preferences;
    }
}
