package com.zkry.trip.dto;

import java.util.Map;

public record GraphNode(
    String id,
    String name,
    Integer category,
    Integer symbolSize,
    Map<String, String> itemStyle,
    String value
) {
}
