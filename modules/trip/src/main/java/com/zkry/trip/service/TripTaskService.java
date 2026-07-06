package com.zkry.trip.service;

import com.zkry.common.core.config.TripstarRuntimeSettingsService;
import com.zkry.common.core.config.TripstarSettingKeys;
import com.zkry.common.core.exception.BizException;
import com.zkry.content.dto.ContentPlanningContext;
import com.zkry.map.dto.MapPlanningContext;
import com.zkry.trip.constant.TripTaskMessages;
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

/**
 * 旅行规划任务状态机。
 *
 * <p>Controller 只负责提交请求；真正的异步执行、阶段推进、WebSocket 事件推送都在这里。
 * 它不直接实现小红书/高德/LLM 细节，而是按阶段调用 {@link TripResearchService}
 * 和 {@link TripAiPlannerService}，让主流程保持可读。
 */
@Service
public class TripTaskService {

    private static final Logger log = LoggerFactory.getLogger(TripTaskService.class);

    private final Map<String, TripTaskState> tasks = new ConcurrentHashMap<>();
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private final TripAiPlannerService tripAiPlannerService;
    private final TripResearchService tripResearchService;
    private final TripstarRuntimeSettingsService runtimeSettingsService;

    public TripTaskService(
        TripAiPlannerService tripAiPlannerService,
        TripResearchService tripResearchService,
        TripstarRuntimeSettingsService runtimeSettingsService
    ) {
        this.tripAiPlannerService = tripAiPlannerService;
        this.tripResearchService = tripResearchService;
        this.runtimeSettingsService = runtimeSettingsService;
    }

    /**
     * 提交旅行规划任务。
     *
     * <p>接口不会同步等 LLM 全部跑完，而是立刻返回 taskId 和 WebSocket 地址。
     * 真正耗时的资料研究、规划、图谱构建会在后台线程里执行。
     */
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
        update(taskId, TripTaskStatus.PROCESSING, TripTaskStage.SUBMITTED, 5, TripTaskMessages.SUBMITTED, null, null);
        CompletableFuture.runAsync(() -> runPlanning(taskId, request), executorService);
        return new SubmitTripPlanResponse(
            taskId,
            taskId,
            TripTaskStatus.PROCESSING,
            "/api/trip/ws/" + taskId,
            TripTaskMessages.SUBMITTED
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
        if (TripTaskStatus.COMPLETED.equals(state.status)) {
            payload.put("result", state.result);
            return payload;
        }
        if (TripTaskStatus.FAILED.equals(state.status)) {
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

    /**
     * 后台任务主流程。
     *
     * <p>这是整套 Java 版 TripStar 的最重要阅读入口：先进入资料研究 Agent，
     * 再进入 Planner/Review Agent，最后把结构化结果推给前端。
     */
    private void runPlanning(String taskId, TripRequest request) {
        long startedAt = System.currentTimeMillis();
        try {
            log.info("[TripTask] 开始执行任务 taskId={} city={} language={} transportation={} accommodation={}",
                taskId, request.primaryCity(), request.safeLanguage(), request.safeTransportation(), request.safeAccommodation());
            pause();
            update(taskId, TripTaskStatus.PROCESSING, TripTaskStage.INITIALIZING, 10, TripTaskMessages.INITIALIZING, null, null);
            pause();
            update(taskId, TripTaskStatus.PROCESSING, TripTaskStage.TRAVEL_RESEARCH, 24, TripTaskMessages.TRAVEL_RESEARCH, null, null);
            TripResearchService.ResearchContext researchContext = tripResearchService.research(taskId, request);
            ContentPlanningContext contentContext = researchContext.contentContext();
            MapPlanningContext mapContext = researchContext.mapContext();
            log.info("[TripTask] 资料研究阶段完成 taskId={} mapRealData={} mapCities={} contentRealData={} contentCities={} summary={}",
                taskId,
                mapContext.realData(),
                mapContext.safeCities().size(),
                contentContext.realData(),
                contentContext.safeCities().size(),
                researchContext.researchResult().safeSummary());
            if (!mapContext.realData()) {
                throw new BizException("高德地图上下文采集失败：" + mapContext.message());
            }
            if (!contentContext.realData()) {
                throw new BizException("小红书内容采集失败：" + contentContext.message());
            }
            pause();
            update(taskId, TripTaskStatus.PROCESSING, TripTaskStage.WEATHER_SEARCH, 46, mapStageMessage(mapContext, "天气"), null, null);
            pause();
            update(taskId, TripTaskStatus.PROCESSING, TripTaskStage.HOTEL_SEARCH, 64, mapStageMessage(mapContext, "酒店和餐饮"), null, null);
            pause();
            update(taskId, TripTaskStatus.PROCESSING, TripTaskStage.PLANNING, 85, TripTaskMessages.PLANNING, null, null);
            TripPlanResponse response = tripAiPlannerService.plan(taskId, request, mapContext, contentContext)
                .orElseThrow(() -> new BizException("Spring AI Alibaba 未能生成可解析的行程 JSON，请检查 AI Key、模型名和提示词约束。"));
            log.info("[TripTask] 规划结果生成 taskId={} days={} graphNodes={}",
                taskId,
                response.data() == null || response.data().days() == null ? 0 : response.data().days().size(),
                response.graph_data() == null || response.graph_data().nodes() == null ? 0 : response.graph_data().nodes().size());
            pause();
            update(taskId, TripTaskStatus.PROCESSING, TripTaskStage.GRAPH_BUILDING, 95, TripTaskMessages.GRAPH_BUILDING, null, null);
            pause();
            update(taskId, TripTaskStatus.COMPLETED, TripTaskStage.COMPLETED, 100, TripTaskMessages.COMPLETED, response, null);
            log.info("[TripTask] 任务执行完成 taskId={} elapsedMs={}", taskId, System.currentTimeMillis() - startedAt);
        } catch (Exception ex) {
            log.error("[TripTask] 任务执行失败 taskId={} elapsedMs={} reason={}",
                taskId, System.currentTimeMillis() - startedAt, ex.getMessage(), ex);
            update(taskId, TripTaskStatus.FAILED, TripTaskStage.FAILED, 100, TripTaskMessages.FAILED, null, ex.getMessage());
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

    /**
     * 校验 Vue 设置页或 application.yml 提供的运行时配置。
     *
     * <p>现在项目不再用模拟数据兜底，缺少 Cookie、地图 Key 或模型配置时会直接失败，
     * 这样日志和前端错误都能明确告诉你缺哪一项。
     */
    private void validateRuntimeSettings() {
        List<String> missing = new ArrayList<>();
        if (!runtimeSettingsService.hasText(TripstarSettingKeys.XHS_COOKIE)) {
            missing.add("小红书 Cookie");
        }
        if (!runtimeSettingsService.hasText(TripstarSettingKeys.AMAP_WEB_KEY)) {
            missing.add("高德地图 Web Service Key");
        }
        if (!runtimeSettingsService.hasText(TripstarSettingKeys.OPENAI_API_KEY)) {
            missing.add("AI API Key");
        }
        if (!runtimeSettingsService.hasText(TripstarSettingKeys.OPENAI_MODEL)) {
            missing.add("AI 模型名称");
        }
        if (!missing.isEmpty()) {
            String message = "缺少运行时配置：" + String.join("、", missing) + "。请先在 Vue 设置页保存后再生成行程。";
            log.warn("[TripTask] 运行时配置校验失败 missing={}", missing);
            throw new BizException(message);
        }
    }

    private String mapStageMessage(MapPlanningContext mapContext, String subject) {
        if (mapContext.realData()) {
            return "已获取地图" + subject + "上下文，正在整理给规划智能体...";
        }
        return mapContext.message() + " 正在继续准备" + subject + "候选信息。";
    }

    /**
     * 更新内存任务状态，并推送给所有 WebSocket 订阅者。
     *
     * <p>前端进度条、轮询接口和最终结果都来自这里维护的 {@link TripTaskState}。
     */
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

        private volatile String status = TripTaskStatus.PROCESSING;
        private volatile String stage = TripTaskStage.SUBMITTED;
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
                TripTaskStatus.FAILED.equals(status) ? requestPayload() : null
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
