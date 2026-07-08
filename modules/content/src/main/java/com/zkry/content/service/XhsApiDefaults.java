package com.zkry.content.service;

/**
 * 小红书 Web 接口的稳定默认值。
 *
 * <p>搜索接口虽然暴露 page_size 字段，但实测传 5 或 10 会返回 success=true 且没有 items；
 * Python 版 TripStar 一直按 20 请求，这里统一固定底层请求大小，再由 Tool 层截取最多 5 条给 Agent。
 */
final class XhsApiDefaults {

    static final int SEARCH_PAGE_SIZE = 20;
    static final int TOOL_RETURN_LIMIT = 5;

    private XhsApiDefaults() {
    }
}
