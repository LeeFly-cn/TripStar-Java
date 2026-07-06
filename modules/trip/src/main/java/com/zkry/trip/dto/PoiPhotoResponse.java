package com.zkry.trip.dto;

public record PoiPhotoResponse(
    Boolean success,
    String message,
    PoiPhotoData data
) {
    public record PoiPhotoData(
        String name,
        String photo_url
    ) {
    }
}
