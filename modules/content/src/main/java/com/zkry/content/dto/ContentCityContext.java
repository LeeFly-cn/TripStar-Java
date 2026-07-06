package com.zkry.content.dto;

import java.util.List;

public record ContentCityContext(
    String city,
    String keyword,
    String source,
    String rawText,
    List<ContentAttractionCandidate> attractions,
    String message
) {
    public List<ContentAttractionCandidate> safeAttractions() {
        return attractions == null ? List.of() : attractions;
    }

    public boolean hasAnyData() {
        return rawText != null && !rawText.isBlank() || !safeAttractions().isEmpty();
    }
}
