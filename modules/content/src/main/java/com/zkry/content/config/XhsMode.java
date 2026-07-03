package com.zkry.content.config;

import java.util.Locale;

/**
 * 小红书资料获取模式。
 *
 * <p>{@link #SERVICE} 是 Java service 直接采集；{@link #TOOL} 是让
 * TravelResearchAgent 调用小红书工具；{@link #BOTH} 会两条链路都跑，适合你
 * 对比“确定性服务编排”和“Agent 自主调工具”的效果差异。
 */
public enum XhsMode {

    SERVICE("service"),
    TOOL("tool"),
    BOTH("both");

    private final String value;

    XhsMode(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public boolean useService() {
        return this == SERVICE || this == BOTH;
    }

    public boolean useTool() {
        return this == TOOL || this == BOTH;
    }

    public static XhsMode from(String value) {
        if (value == null || value.isBlank()) {
            return SERVICE;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (XhsMode mode : values()) {
            if (mode.value.equals(normalized)) {
                return mode;
            }
        }
        return SERVICE;
    }
}
