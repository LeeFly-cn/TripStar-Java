package com.zkry.map.dto;

public record MapPoint(
    Double longitude,
    Double latitude
) {
    public boolean available() {
        return longitude != null && latitude != null;
    }
}
