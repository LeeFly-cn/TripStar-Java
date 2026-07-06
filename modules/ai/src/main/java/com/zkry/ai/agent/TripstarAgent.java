package com.zkry.ai.agent;

/**
 * TripStar 内部 Agent 名称注册表。
 *
 * <p>Spring AI Alibaba 的 ReactAgent 需要一个 name。之前如果到处写字符串，
 * 改名或排查日志会很痛苦；现在所有 Agent 都从这里取 id。
 */
public enum TripstarAgent {

    XHS_EXTRACTION("xhs-extraction-agent"),
    TRAVEL_RESEARCH("travel-research-agent"),
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
