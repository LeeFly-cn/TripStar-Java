package com.zkry.ai.agent;

/**
 * TripStar 内部 Agent 名称注册表。
 *
 * <p>Spring AI Alibaba 的 ReactAgent 需要一个 name。之前如果到处写字符串，
 * 改名或排查日志会很痛苦；现在所有 Agent 都从这里取 id。
 */
public enum TripstarAgent {

    XHS_EXTRACTION("xhs-extraction-agent"),
    XHS_SEARCH("xhs-search-agent"),
    XHS_DETAIL("xhs-detail-agent"),
    AMAP_POI_RESEARCH("amap-poi-research-agent"),
    AMAP_WEATHER_RESEARCH("amap-weather-research-agent"),
    AMAP_HOTEL_RESEARCH("amap-hotel-research-agent"),
    TRIP_PLANNER("trip-planner-agent"),
    TRIP_REVIEW("trip-review-agent"),
    TRIP_CHAT("trip-chat-agent");

    private final String id;

    TripstarAgent(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}
