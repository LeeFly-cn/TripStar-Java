package com.zkry.trip.service;

import com.zkry.common.core.config.TripstarRuntimeSettingsService;
import com.zkry.common.core.exception.BizException;
import com.zkry.content.dto.ContentCityRequest;
import com.zkry.content.dto.ContentPlanningContext;
import com.zkry.content.service.TravelContentService;
import com.zkry.map.dto.MapCityRequest;
import com.zkry.map.dto.MapPlanningContext;
import com.zkry.map.service.MapContextService;
import com.zkry.trip.dto.SubmitTripPlanResponse;
import com.zkry.trip.dto.TripPlanResponse;
import com.zkry.trip.dto.TripRequest;
import com.zkry.trip.dto.TripTaskEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TripTaskService {

    private static final Logger log = LoggerFactory.getLogger(TripTaskService.class);

    private static final String STATUS_PROCESSING = "processing";
    private static final String STATUS_COMPLETED = "completed";
    private static final String STATUS_FAILED = "failed";

    private final Map<String, TripTaskState> tasks = new ConcurrentHashMap<>();
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private final TripAiPlannerService tripAiPlannerService;
    private final MapContextService mapContextService;
    private final TravelContentService travelContentService;
    private final TripstarRuntimeSettingsService runtimeSettingsService;

    public TripTaskService(
        TripAiPlannerService tripAiPlannerService,
        MapContextService mapContextService,
        TravelContentService travelContentService,
        TripstarRuntimeSettingsService runtimeSettingsService
    ) {
        this.tripAiPlannerService = tripAiPlannerService;
        this.mapContextService = mapContextService;
        this.travelContentService = travelContentService;
        this.runtimeSettingsService = runtimeSettingsService;
    }

    public SubmitTripPlanResponse submit(TripRequest request) {
        validateTripRequest(request);
        validateRuntimeSettings();
        String taskId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        TripTaskState state = new TripTaskState(taskId, request);
        tasks.put(taskId, state);
        log.info("[TripTask] 创建旅行规划任务 taskId={} cities={} totalDays={} date={}~{} preferences={} aiAvailable={}",
            taskId,
            request.normalizedCities().stream().map(city -> city.city() + ":" + city.safeDays() + "天").toList(),
            request.safeTravelDays(),
            safeLog(request.start_date()),
            safeLog(request.end_date()),
            request.safePreferences(),
            tripAiPlannerService.isAvailable());
        update(taskId, STATUS_PROCESSING, "submitted", 5, "任务已提交，正在初始化流程...", null, null);
        CompletableFuture.runAsync(() -> runPlanning(taskId, request), executorService);
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
        log.info("[TripTask] 新增任务订阅 taskId={} subscriberCount={}", taskId, state.subscribers.size());
        return new TripTaskSubscription(taskId, () -> {
            state.subscribers.remove(subscriber);
            log.info("[TripTask] 取消任务订阅 taskId={} subscriberCount={}", taskId, state.subscribers.size());
        });
    }

    private TripTaskState task(String taskId) {
        TripTaskState state = tasks.get(taskId);
        if (state == null) {
            throw new TripTaskNotFoundException(taskId);
        }
        return state;
    }

    private void runPlanning(String taskId, TripRequest request) {
        long startedAt = System.currentTimeMillis();
        try {
            log.info("[TripTask] 开始执行任务 taskId={} city={} language={} transportation={} accommodation={}",
                taskId, request.primaryCity(), request.safeLanguage(), request.safeTransportation(), request.safeAccommodation());
            pause();
            update(taskId, STATUS_PROCESSING, "initializing", 10, "正在初始化 Spring AI Alibaba 旅行规划工作流...", null, null);
            pause();
            update(taskId, STATUS_PROCESSING, "attraction_search", 24, "正在搜索小红书游记和景点候选数据...", null, null);
            // 小红书负责真实游记、避坑建议和景点图片，是 Python 版 TripStar 的核心内容源。
            ContentPlanningContext contentContext = collectContentContext(request);
            log.info("[TripTask] 小红书内容阶段完成 taskId={} realData={} cities={} message={}",
                taskId, contentContext.realData(), contentContext.safeCities().size(), contentContext.message());
            if (!contentContext.realData()) {
                throw new BizException("小红书内容采集失败：" + contentContext.message());
            }
            pause();
            update(taskId, STATUS_PROCESSING, "attraction_search", 34, contentStageMessage(contentContext), null, null);
            // 地图上下文负责 POI、酒店、餐饮、天气和坐标。现在不再生成模拟行程，采集不到真实上下文就明确失败。
            MapPlanningContext mapContext = collectMapContext(request);
            log.info("[TripTask] 地图上下文阶段完成 taskId={} realData={} cities={} message={}",
                taskId, mapContext.realData(), mapContext.safeCities().size(), mapContext.message());
            if (!mapContext.realData()) {
                throw new BizException("高德地图上下文采集失败：" + mapContext.message());
            }
            pause();
            update(taskId, STATUS_PROCESSING, "weather_search", 46, mapStageMessage(mapContext, "天气"), null, null);
            pause();
            update(taskId, STATUS_PROCESSING, "hotel_search", 64, mapStageMessage(mapContext, "酒店和餐饮"), null, null);
            pause();
            update(taskId, STATUS_PROCESSING, "planning", 85, "正在调用 Spring AI Alibaba 生成行程结构...", null, null);
            TripPlanResponse response = tripAiPlannerService.plan(taskId, request, mapContext, contentContext)
                .orElseThrow(() -> new BizException("Spring AI Alibaba 未能生成可解析的行程 JSON，请检查 AI Key、模型名和提示词约束。"));
            log.info("[TripTask] 规划结果生成 taskId={} days={} graphNodes={}",
                taskId,
                response.data() == null || response.data().days() == null ? 0 : response.data().days().size(),
                response.graph_data() == null || response.graph_data().nodes() == null ? 0 : response.graph_data().nodes().size());
            pause();
            update(taskId, STATUS_PROCESSING, "graph_building", 95, "正在构建知识图谱...", null, null);
            pause();
            update(taskId, STATUS_COMPLETED, "completed", 100, "旅行计划生成成功", response, null);
            log.info("[TripTask] 任务执行完成 taskId={} elapsedMs={}", taskId, System.currentTimeMillis() - startedAt);
        } catch (Exception ex) {
            log.error("[TripTask] 任务执行失败 taskId={} elapsedMs={} reason={}",
                taskId, System.currentTimeMillis() - startedAt, ex.getMessage(), ex);
            update(taskId, STATUS_FAILED, "failed", 100, "旅行计划生成失败", null, ex.getMessage());
        }
    }

    private void validateTripRequest(TripRequest request) {
        if (request == null) {
            throw new BizException("行程请求不能为空。");
        }
        if (request.normalizedCities().isEmpty()) {
            throw new BizException("请至少填写一个目的地城市。");
        }
        if (request.safeTravelDays() <= 0) {
            throw new BizException("旅行天数必须大于 0。");
        }
    }

    private void validateRuntimeSettings() {
        List<String> missing = new ArrayList<>();
        if (!runtimeSettingsService.hasText("xhs_cookie")) {
            missing.add("小红书 Cookie");
        }
        if (!runtimeSettingsService.hasText("vite_amap_web_key")) {
            missing.add("高德地图 Web Service Key");
        }
        if (!runtimeSettingsService.hasText("openai_api_key")) {
            missing.add("AI API Key");
        }
        if (!runtimeSettingsService.hasText("openai_model")) {
            missing.add("AI 模型名称");
        }
        if (!missing.isEmpty()) {
            String message = "缺少运行时配置：" + String.join("、", missing) + "。请先在 Vue 设置页保存后再生成行程。";
            log.warn("[TripTask] 运行时配置校验失败 missing={}", missing);
            throw new BizException(message);
        }
    }

    /**
     * 收集真实游记内容。小红书失败时返回空上下文，主任务继续执行地图和规划阶段。
     */
    private ContentPlanningContext collectContentContext(TripRequest request) {
        try {
            List<ContentCityRequest> cityRequests = request.normalizedCities().stream()
                .map(city -> new ContentCityRequest(
                    city.city(),
                    city.safeDays(),
                    request.safePreferences(),
                    request.safeLanguage()
                ))
                .toList();
            log.info("[TripTask] 准备采集小红书内容 cities={}", cityRequests.stream().map(ContentCityRequest::city).toList());
            return travelContentService.collect(cityRequests);
        } catch (Exception ex) {
            log.warn("[TripTask] 小红书内容采集降级 reason={}", ex.getMessage());
            return ContentPlanningContext.empty("xhs", "小红书内容采集失败：" + ex.getMessage());
        }
    }

    /**
     * 收集地图、酒店、餐饮、天气上下文。第三方地图失败时降级，不让整条行程任务失败。
     */
    private MapPlanningContext collectMapContext(TripRequest request) {
        try {
            List<MapCityRequest> cityRequests = request.normalizedCities().stream()
                .map(city -> new MapCityRequest(
                    city.city(),
                    city.safeDays(),
                    request.safePreferences(),
                    request.safeAccommodation()
                ))
                .toList();
            log.info("[TripTask] 准备采集地图上下文 cities={}", cityRequests.stream().map(MapCityRequest::city).toList());
            return mapContextService.collect(cityRequests);
        } catch (Exception ex) {
            log.warn("[TripTask] 地图上下文采集降级 reason={}", ex.getMessage());
            return MapPlanningContext.empty("amap", "地图上下文采集失败：" + ex.getMessage());
        }
    }

    private String contentStageMessage(ContentPlanningContext contentContext) {
        if (contentContext.realData()) {
            return "已获取小红书游记内容，正在补充地图坐标和天气上下文...";
        }
        return contentContext.message() + " 正在继续查询地图景点候选数据。";
    }

    private String mapStageMessage(MapPlanningContext mapContext, String subject) {
        if (mapContext.realData()) {
            return "已获取地图" + subject + "上下文，正在整理给规划智能体...";
        }
        return mapContext.message() + " 正在继续准备" + subject + "候选信息。";
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
        log.info("[TripTask] 进度更新 taskId={} status={} stage={} progress={} message={}",
            taskId, status, stage, progress, message);
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

    private String safeLog(String value) {
        return value == null || value.isBlank() ? "-" : value;
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
