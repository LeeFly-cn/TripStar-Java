package com.zkry.trip.dto;

import java.util.List;
import java.util.Map;

public record TripChatRequest(
    String message,
    Map<String, Object> trip_plan,
    List<ChatMessage> history
) {
}
