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
    public static final String TRAVEL_RESEARCH = "正在调用资料研究智能体，读取小红书和高德工具...";
    public static final String PLANNING = "正在调用 Spring AI Alibaba 生成行程结构...";
    public static final String GRAPH_BUILDING = "正在构建知识图谱...";
    public static final String COMPLETED = "旅行计划生成成功";
    public static final String FAILED = "旅行计划生成失败";

    private TripTaskMessages() {
    }
}
