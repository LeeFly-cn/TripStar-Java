package com.zkry.content.dto;

import java.util.Map;

public record ContentAttractionCandidate(
    String name,
    String name_zh,
    String name_en,
    String reason,
    Integer duration,
    Boolean reservation_required,
    String reservation_tips,
    Map<String, Object> location
) {
}
