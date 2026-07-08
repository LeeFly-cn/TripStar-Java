package com.zkry.trip.dto;

import java.util.List;

/**
 * 某个城市的小红书搜索结果。
 */
public record XhsCitySearchResult(
    String city,
    String keyword,
    List<XhsNoteRef> notes,
    String message
) {
    public List<XhsNoteRef> safeNotes() {
        return notes == null ? List.of() : notes;
    }
}
