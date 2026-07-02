package com.zkry.map.dto;

public record MapPoi(
    String name,
    String address,
    MapPoint location,
    String type,
    String rating,
    String distance,
    String photoUrl
) {
}
