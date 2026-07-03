package com.zkry.trip.service;

/**
 * 任务状态值。
 *
 * <p>状态表示任务生命周期，阶段表示当前执行到哪一步。前端轮询接口和 WebSocket
 * 都会读取这些值。
 */
public final class TripTaskStatus {

    public static final String PROCESSING = "processing";
    public static final String COMPLETED = "completed";
    public static final String FAILED = "failed";

    private TripTaskStatus() {
    }
}
