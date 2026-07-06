package com.zkry.content.dto;

import java.util.List;

public record ContentCityRequest(
    String city,
    int days,
    List<String> preferences,
    String language
) {
    public List<String> safePreferences() {
        return preferences == null ? List.of() : preferences;
    }

    public String keyword() {
        return safePreferences().stream()
            .filter(value -> value != null && !value.isBlank())
            .findFirst()
            .orElse("景点");
    }

    public String safeLanguage() {
        return language == null || language.isBlank() ? "zh" : language;
    }
}
