package com.zkry.common.core.constant;

/**
 * 旅行规划上下文的数据来源标识。
 *
 * <p>这些值会进入日志、Agent 结构化输出和前端调试面板。它们不是业务逻辑判断的
 * 唯一依据，但能帮助你快速看出一段地图/游记上下文来自高德、小红书 service，
 * 还是由 Agent 调工具生成。
 */
public final class TravelDataSource {

    public static final String NONE = "none";
    public static final String AMAP = "amap";
    public static final String XHS = "xhs";
    public static final String XHS_SERVICE = "xhs-service";
    public static final String XHS_BOTH = "xhs-both";
    // 用户指定公开笔记，与搜索 API 的 xhs/xhs-service 来源区分开。
    public static final String XHS_NOTE = "xhs-note";
    public static final String AGENT_TOOL = "agent-tool";

    private TravelDataSource() {
    }
}
