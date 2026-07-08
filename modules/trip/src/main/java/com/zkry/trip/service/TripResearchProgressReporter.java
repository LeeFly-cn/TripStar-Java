package com.zkry.trip.service;

/**
 * 资料研究阶段进度回调。
 *
 * <p>{@link TripResearchService} 不直接持有任务状态，但它最清楚每个 Agent 阶段何时
 * 真正开始执行。因此用这个小接口把阶段变化回传给 {@link TripTaskService}，前端看到的
 * 进度就能对应真实工具调用，而不是靠固定 pause 模拟。
 */
@FunctionalInterface
public interface TripResearchProgressReporter {

    void report(String stage, int progress, String message);

    static TripResearchProgressReporter noop() {
        return (stage, progress, message) -> {
        };
    }
}
