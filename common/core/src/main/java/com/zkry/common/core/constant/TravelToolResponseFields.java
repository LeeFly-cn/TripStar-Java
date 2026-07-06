package com.zkry.common.core.constant;

/**
 * Agent 工具返回给 LLM 的 JSON 字段名。
 *
 * <p>AMap/XHS 工具都会返回同一类结构：
 * {@code success/source/tool/data/error}。把字段名集中在这里，能保证提示词、
 * 日志和工具实现对齐，也方便你以后扩展更多工具。
 */
public final class TravelToolResponseFields {

    public static final String SUCCESS = "success";
    public static final String SOURCE = "source";
    public static final String TOOL = "tool";
    public static final String DATA = "data";
    public static final String ERROR = "error";

    private TravelToolResponseFields() {
    }
}
