package com.zkry.trip.dto.xhsnote;

import java.util.List;

/**
 * 从笔记正文或图片中识别出的单日路线。
 *
 * @param day 第几天，从 1 开始
 * @param title 笔记中的 Day 标题或模型概括标题
 * @param places 当天按游览顺序排列的景点名称
 * @param hotel_area 笔记明确推荐的住宿或住宿区域
 * @param breakfast_area 早餐或早餐区域
 * @param lunch_area 午餐或午餐区域
 * @param dinner_area 晚餐或晚餐区域
 * @param note 当天交通、预约或节奏等补充说明
 */
public record XhsNoteDayRoute(
    Integer day,
    String title,
    List<String> places,
    String hotel_area,
    String breakfast_area,
    String lunch_area,
    String dinner_area,
    String note
) {
    /** 返回只读安全列表，方便路线锚点计算时直接调用。 */
    public List<String> safePlaces() {
        return places == null ? List.of() : places;
    }
}
