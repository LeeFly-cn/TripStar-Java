package com.zkry.trip.service;

import com.zkry.trip.dto.SubmitTripPlanResponse;
import com.zkry.trip.dto.TripPlanResponse;
import com.zkry.trip.dto.TripRequest;
import com.zkry.trip.dto.TripTaskEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.stereotype.Service;

@Service
public class TripTaskService {

    private static final String STATUS_PROCESSING = "processing";
    private static final String STATUS_COMPLETED = "completed";
    private static final String STATUS_FAILED = "failed";

    private final Map<String, TripTaskState> tasks = new ConcurrentHashMap<>();
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    public SubmitTripPlanResponse submit(TripRequest request) {
        String taskId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        TripTaskState state = new TripTaskState(taskId, request);
        tasks.put(taskId, state);
        update(taskId, STATUS_PROCESSING, "submitted", 5, "任务已提交，正在初始化流程...", null, null);
        CompletableFuture.runAsync(() -> runMockPlanning(taskId, request), executorService);
        return new SubmitTripPlanResponse(
            taskId,
            taskId,
            STATUS_PROCESSING,
            "/api/trip/ws/" + taskId,
            "任务已提交，正在初始化流程..."
        );
    }

    public TripTaskEvent snapshot(String taskId) {
        TripTaskState state = task(taskId);
        return state.toEvent(true);
    }

    public Map<String, Object> status(String taskId) {
        TripTaskState state = task(taskId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("task_id", state.taskId);
        payload.put("plan_id", state.taskId);
        payload.put("status", state.status);
        if (STATUS_COMPLETED.equals(state.status)) {
            payload.put("result", state.result);
            return payload;
        }
        if (STATUS_FAILED.equals(state.status)) {
            payload.put("error", state.error);
            payload.put("request_payload", state.requestPayload());
            return payload;
        }
        payload.put("stage", state.stage);
        payload.put("progress", state.progress);
        payload.put("progress_text", state.message);
        return payload;
    }

    public TripTaskSubscription subscribe(String taskId, TripTaskSubscriber subscriber) {
        TripTaskState state = task(taskId);
        state.subscribers.add(subscriber);
        return new TripTaskSubscription(taskId, () -> state.subscribers.remove(subscriber));
    }

    private TripTaskState task(String taskId) {
        TripTaskState state = tasks.get(taskId);
        if (state == null) {
            throw new TripTaskNotFoundException(taskId);
        }
        return state;
    }

    private void runMockPlanning(String taskId, TripRequest request) {
        try {
            pause();
            update(taskId, STATUS_PROCESSING, "initializing", 10, "正在初始化 Spring AI Alibaba 旅行规划工作流...", null, null);
            pause();
            update(taskId, STATUS_PROCESSING, "attraction_search", 28, "正在准备景点候选数据（当前为 Java mock 数据源）...", null, null);
            pause();
            update(taskId, STATUS_PROCESSING, "weather_search", 46, "正在准备天气信息（后续接入地图天气 API）...", null, null);
            pause();
            update(taskId, STATUS_PROCESSING, "hotel_search", 64, "正在准备酒店推荐（后续接入 POI 搜索）...", null, null);
            pause();
            update(taskId, STATUS_PROCESSING, "planning", 85, "正在生成行程结构（后续替换为 Spring AI Alibaba 调用）...", null, null);
            TripPlanResponse response = TripMockPlanFactory.create(taskId, request);
            pause();
            update(taskId, STATUS_PROCESSING, "graph_building", 95, "正在构建知识图谱...", null, null);
            pause();
            update(taskId, STATUS_COMPLETED, "completed", 100, "旅行计划生成成功", response, null);
        } catch (Exception ex) {
            update(taskId, STATUS_FAILED, "failed", 100, "旅行计划生成失败", null, ex.getMessage());
        }
    }

    private void update(
        String taskId,
        String status,
        String stage,
        int progress,
        String message,
        TripPlanResponse result,
        String error
    ) {
        TripTaskState state = tasks.get(taskId);
        if (state == null) {
            return;
        }
        state.status = status;
        state.stage = stage;
        state.progress = progress;
        state.message = message;
        if (result != null) {
            state.result = result;
        }
        if (error != null) {
            state.error = error;
        }
        TripTaskEvent event = state.toEvent(true);
        for (TripTaskSubscriber subscriber : state.subscribers) {
            subscriber.onEvent(event);
        }
    }

    private void pause() {
        try {
            Thread.sleep(450L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("任务被中断", ex);
        }
    }

    private static final class TripTaskState {

        private final String taskId;
        private final TripRequest request;
        private final CopyOnWriteArrayList<TripTaskSubscriber> subscribers = new CopyOnWriteArrayList<>();

        private volatile String status = STATUS_PROCESSING;
        private volatile String stage = "submitted";
        private volatile int progress = 0;
        private volatile String message = "";
        private volatile String error = "";
        private volatile TripPlanResponse result;

        private TripTaskState(String taskId, TripRequest request) {
            this.taskId = taskId;
            this.request = request;
        }

        private TripTaskEvent toEvent(boolean includeResult) {
            return new TripTaskEvent(
                taskId,
                taskId,
                status,
                stage,
                progress,
                message,
                error == null || error.isBlank() ? null : error,
                includeResult ? result : null,
                STATUS_FAILED.equals(status) ? requestPayload() : null
            );
        }

        private Map<String, Object> requestPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("city", request.city());
            payload.put("cities", request.cities());
            payload.put("start_date", request.start_date());
            payload.put("end_date", request.end_date());
            payload.put("travel_days", request.travel_days());
            payload.put("transportation", request.transportation());
            payload.put("accommodation", request.accommodation());
            payload.put("preferences", request.preferences());
            payload.put("free_text_input", request.free_text_input());
            payload.put("language", request.language());
            return payload;
        }
    }
}
