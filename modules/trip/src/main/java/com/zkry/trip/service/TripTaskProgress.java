package com.zkry.trip.service;

/**
 * 前端进度条使用的百分比节点。
 *
 * <p>Landing 页的四段 stepper 目前按数值区间展示：
 * 0~30 为景点资料搜索，31~50 为天气，51~70 为酒店餐饮，70 以后为生成行程。
 * 后端子阶段可以更细，但数值必须落在对应的大阶段里，否则前端会显示到错误步骤。
 */
public final class TripTaskProgress {

    public static final int SUBMITTED = 5;
    public static final int INITIALIZING = 10;
    public static final int TRAVEL_RESEARCH = 16;
    public static final int XHS_SERVICE_SEARCH = 20;
    public static final int XHS_SEARCH = 22;
    public static final int XHS_DETAIL = 26;

    // 指定笔记模式在 30% 前完成链接解析、图片下载和多模态理解，之后复用地图阶段进度。
    public static final int XHS_NOTE_RESOLVE = 18;
    public static final int XHS_NOTE_IMAGE = 23;
    public static final int XHS_NOTE_UNDERSTANDING = 28;

    public static final int AMAP_POI_SEARCH = 30;
    public static final int WEATHER_SEARCH = 46;
    public static final int HOTEL_SEARCH = 64;
    public static final int RESEARCH_MERGE = 74;
    public static final int PLANNING = 85;
    public static final int GRAPH_BUILDING = 95;
    public static final int DONE = 100;

    private TripTaskProgress() {
    }
}
