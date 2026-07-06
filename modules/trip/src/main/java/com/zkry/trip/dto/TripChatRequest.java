package com.zkry.trip.dto;

import java.util.List;

public record TripChatRequest(
    String message,
    Object trip_plan,
    List<ChatMessage> history
) {
}
