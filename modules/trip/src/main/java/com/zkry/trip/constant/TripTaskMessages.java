package com.zkry.trip.constant;

/**
 * 异步任务进度文案。
 *
 * <p>前端 WebSocket 进度条直接展示这些 message。集中管理以后，任务状态机
 * {@code TripTaskService} 只表达“进入哪个阶段”，不再夹杂大量展示文案。
 */
public final class TripTaskMessages {

    public static final String SUBMITTED = "任务已提交，正在初始化流程...";
    public static final String INITIALIZING = "正在初始化 Spring AI Alibaba 旅行规划工作流...";
    public static final String TRAVEL_RESEARCH = "正在启动分阶段资料研究工作流...";
    public static final String XHS_SEARCH = "正在调用小红书搜索智能体，检索真实游记笔记...";
    public static final String XHS_DETAIL = "正在调用小红书详情智能体，读取笔记正文并提炼景点...";

    // 指定笔记模式专用文案：阶段值与自主规划共用，显示文字可以更准确描述当前工作。
    public static final String XHS_NOTE_SUBMITTED = "指定笔记规划任务已提交，正在初始化...";
    public static final String XHS_NOTE_INITIALIZING = "正在初始化小红书指定笔记规划流程...";
    public static final String XHS_NOTE_RESOLVE = "正在解析小红书分享内容和短链接...";
    public static final String XHS_NOTE_IMAGE = "正在读取笔记正文并下载全部图片...";
    public static final String XHS_NOTE_UNDERSTANDING = "正在使用多模态模型识别目的地、天数、路线和笔记图片...";
    public static final String XHS_NOTE_POI_VALIDATE = "正在通过高德 Service 校验并补全笔记地点...";
    public static final String XHS_NOTE_HOTEL_FOOD = "正在根据每日路线补充缺失的酒店和餐饮...";

    public static final String AMAP_POI_SEARCH = "正在调用高德 POI 智能体，查询景点和经纬度...";
    public static final String WEATHER_SEARCH = "正在调用高德天气智能体，查询天气预报...";
    public static final String HOTEL_SEARCH = "正在调用高德酒店智能体，搜索酒店和餐饮...";
    public static final String RESEARCH_MERGE = "正在合并小红书和高德上下文...";
    public static final String PLANNING = "正在调用 Spring AI Alibaba 生成行程结构...";
    public static final String GRAPH_BUILDING = "正在构建知识图谱...";
    public static final String COMPLETED = "旅行计划生成成功";
    public static final String FAILED = "旅行计划生成失败";

    private TripTaskMessages() {
    }
}
