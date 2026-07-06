package com.zkry.ai.prompt;

/**
 * TripStar prompt 模板变量名。
 *
 * <p>Prompt 文件使用 {@code {{变量名}}} 占位，Java 代码通过这些常量填充变量。
 * 这样改变量名时能靠编译器发现调用点，避免散落的字符串写错。
 */
public final class TripstarPromptVariable {

    public static final String FORMAT = "format";
    public static final String CITY = "city";
    public static final String KEYWORD = "keyword";
    public static final String LANGUAGE = "language";
    public static final String RAW_TEXT = "raw_text";
    public static final String XHS_MODE = "xhs_mode";
    public static final String TRAVEL_DAYS = "travel_days";
    public static final String CITY_NAMES = "city_names";
    public static final String TRIP_PLAN_JSON = "trip_plan_json";
    public static final String MESSAGE = "message";
    public static final String TRIP_PLAN = "trip_plan";

    private TripstarPromptVariable() {
    }
}
