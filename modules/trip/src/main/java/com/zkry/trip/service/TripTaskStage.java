package com.zkry.trip.service;

/**
 * 前端进度条识别的任务阶段值。
 *
 * <p>新增阶段时先改这里，再同步前端的阶段类型和展示文案。不要在业务代码里裸写
 * {@code "travel_research"} 这类字符串。
 */
public final class TripTaskStage {

    public static final String SUBMITTED = "submitted";
    public static final String INITIALIZING = "initializing";
    public static final String TRAVEL_RESEARCH = "travel_research";
    public static final String WEATHER_SEARCH = "weather_search";
    public static final String HOTEL_SEARCH = "hotel_search";
    public static final String PLANNING = "planning";
    public static final String GRAPH_BUILDING = "graph_building";
    public static final String COMPLETED = "completed";
    public static final String FAILED = "failed";

    private TripTaskStage() {
    }
}
