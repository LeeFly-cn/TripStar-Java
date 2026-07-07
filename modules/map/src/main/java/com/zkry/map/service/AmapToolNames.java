package com.zkry.map.service;

/**
 * 暴露给 ReactAgent 的高德工具名称。
 *
 * <p>这些名字会出现在 prompt 和工具调用日志里，改名时需要同步
 * {@code prompts/tripstar/research-*.md}。
 */
public final class AmapToolNames {

    public static final String GEOCODE = "amap_geocode";
    public static final String POI_SEARCH = "amap_poi_search";
    public static final String HOTEL_SEARCH = "amap_hotel_search";
    public static final String RESTAURANT_SEARCH = "amap_restaurant_search";
    public static final String WEATHER = "amap_weather";

    private AmapToolNames() {
    }
}
