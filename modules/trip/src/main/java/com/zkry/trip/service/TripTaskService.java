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
import com.zkry.trip.dto.xhsnote.XhsNotePlanRequest;
import com.zkry.trip.dto.xhsnote.XhsNoteResearchContext;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
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
 * 它不直接实现小红书/高德/LLM 细节，而是按入口调用 {@link TripResearchService} 或
 * {@link XhsNoteResearchService}，最后统一交给 {@link TripAiPlannerService}，让主流程保持可读。
 */
@Service
public class TripTaskService {

    private static final Logger log = LoggerFactory.getLogger(TripTaskService.class);

    private final Map<String, TripTaskState> tasks = new ConcurrentHashMap<>();
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private final TripAiPlannerService tripAiPlannerService;
    private final TripResearchService tripResearchService;
    private final XhsNoteResearchService xhsNoteResearchService;
    private final XhsNotePlanPhotoEnricher xhsNotePlanPhotoEnricher;
    private final TripstarRuntimeSettingsService runtimeSettingsService;

    public TripTaskService(
        TripAiPlannerService tripAiPlannerService,
        TripResearchService tripResearchService,
        XhsNoteResearchService xhsNoteResearchService,
        XhsNotePlanPhotoEnricher xhsNotePlanPhotoEnricher,
        TripstarRuntimeSettingsService runtimeSettingsService
    ) {
        this.tripAiPlannerService = tripAiPlannerService;
        this.tripResearchService = tripResearchService;
        this.xhsNoteResearchService = xhsNoteResearchService;
        this.xhsNotePlanPhotoEnricher = xhsNotePlanPhotoEnricher;
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
        TripTaskState state = new TripTaskState(taskId, tripRequestPayload(request));
        tasks.put(taskId, state);
        log.info("[TripTask] 创建旅行规划任务 taskId={} cities={} totalDays={} date={}~{} preferences={} aiAvailable={}",
            taskId,
            request.normalizedCities().stream().map(city -> city.city() + ":" + city.safeDays() + "天").toList(),
            request.safeTravelDays(),
            safeLog(request.start_date()),
            safeLog(request.end_date()),
            request.safePreferences(),
            tripAiPlannerService.isAvailable());
        update(taskId, TripTaskStatus.PROCESSING, TripTaskStage.SUBMITTED, TripTaskProgress.SUBMITTED, TripTaskMessages.SUBMITTED, null, null);
        CompletableFuture.runAsync(() -> runPlanning(taskId, request), executorService);
        return new SubmitTripPlanResponse(
            taskId,
            taskId,
            TripTaskStatus.PROCESSING,
            "/api/trip/ws/" + taskId,
            TripTaskMessages.SUBMITTED
        );
    }

    /**
     * 提交指定小红书笔记规划任务。
     *
     * <p>这个方法只做同步校验、创建内存任务和启动异步线程，因此 HTTP 接口可以立即返回。
     * 后续所有阶段进度都通过同一个 {@code taskId} 推送到原有 WebSocket。
     */
    public SubmitTripPlanResponse submitXhsNote(XhsNotePlanRequest request) {
        // 先校验请求和运行时配置，避免创建一个必然失败的异步任务。
        validateXhsNoteRequest(request);
        validateXhsNoteRuntimeSettings();

        // 指定笔记请求不是 TripRequest，因此任务状态改为保存通用 Map 快照。
        String taskId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        TripTaskState state = new TripTaskState(taskId, xhsNoteRequestPayload(request));
        tasks.put(taskId, state);

        log.info("[TripTask] 创建指定笔记规划任务 taskId={} startDate={} shareTextLength={} noteContentLength={}",
                taskId,
                request.safeStartDate(),
                request.safeShareText().length(),
                request.safeNoteContent().length());

        // 第一次 update 会同时写入任务状态，并向已经连接的订阅者推送 submitted 事件。
        update(
                taskId,
                TripTaskStatus.PROCESSING,
                TripTaskStage.SUBMITTED,
                TripTaskProgress.SUBMITTED,
                TripTaskMessages.XHS_NOTE_SUBMITTED,
                null,
                null
        );

        // 真正的网络读取、模型调用和 Planner 执行放入线程池，避免占用 HTTP 请求线程。
        CompletableFuture.runAsync(
                () -> runXhsNotePlanning(taskId, request),
                executorService
        );

        return new SubmitTripPlanResponse(
                taskId,
                taskId,
                TripTaskStatus.PROCESSING,
                "/api/trip/ws/" + taskId,
                TripTaskMessages.XHS_NOTE_SUBMITTED
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
            update(taskId, TripTaskStatus.PROCESSING, TripTaskStage.INITIALIZING, TripTaskProgress.INITIALIZING, TripTaskMessages.INITIALIZING, null, null);
            pause();
            update(taskId, TripTaskStatus.PROCESSING, TripTaskStage.TRAVEL_RESEARCH, TripTaskProgress.TRAVEL_RESEARCH, TripTaskMessages.TRAVEL_RESEARCH, null, null);
            TripResearchService.ResearchContext researchContext = tripResearchService.research(
                taskId,
                request,
                (stage, progress, message) -> update(taskId, TripTaskStatus.PROCESSING, stage, progress, message, null, null)
            );
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
            update(taskId, TripTaskStatus.PROCESSING, TripTaskStage.PLANNING, TripTaskProgress.PLANNING, TripTaskMessages.PLANNING, null, null);
            TripPlanResponse response = tripAiPlannerService.plan(taskId, request, mapContext, contentContext)
                .orElseThrow(() -> new BizException("Spring AI Alibaba 未能生成可解析的行程 JSON，请检查 AI Key、模型名和提示词约束。"));
            log.info("[TripTask] 规划结果生成 taskId={} days={} graphNodes={}",
                taskId,
                response.data() == null || response.data().days() == null ? 0 : response.data().days().size(),
                response.graph_data() == null || response.graph_data().nodes() == null ? 0 : response.graph_data().nodes().size());
            pause();
            update(taskId, TripTaskStatus.PROCESSING, TripTaskStage.GRAPH_BUILDING, TripTaskProgress.GRAPH_BUILDING, TripTaskMessages.GRAPH_BUILDING, null, null);
            pause();
            update(taskId, TripTaskStatus.COMPLETED, TripTaskStage.COMPLETED, TripTaskProgress.DONE, TripTaskMessages.COMPLETED, response, null);
            log.info("[TripTask] 任务执行完成 taskId={} elapsedMs={}", taskId, System.currentTimeMillis() - startedAt);
        } catch (Exception ex) {
            log.error("[TripTask] 任务执行失败 taskId={} elapsedMs={} reason={}",
                taskId, System.currentTimeMillis() - startedAt, ex.getMessage(), ex);
            update(taskId, TripTaskStatus.FAILED, TripTaskStage.FAILED, TripTaskProgress.DONE, TripTaskMessages.FAILED, null, ex.getMessage());
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

    /** 校验指定笔记接口必需字段；不在这里推断城市和天数。 */
    private void validateXhsNoteRequest(XhsNotePlanRequest request) {
        if (request == null) {
            throw new BizException("指定笔记规划请求不能为空。");
        }
        if (!request.hasAnyNoteContent()) {
            throw new BizException("请填写小红书笔记链接或攻略笔记内容。");
        }
        if (request.safeStartDate().isBlank()) {
            throw new BizException("请选择出发日期。");
        }
        try {
            // LocalDate.parse 默认要求 ISO yyyy-MM-dd，和前端 DatePicker 提交格式一致。
            LocalDate.parse(request.safeStartDate());
        } catch (DateTimeParseException ex) {
            throw new BizException("出发日期格式必须为 yyyy-MM-dd。");
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

    /**
     * 校验指定笔记模式的外部依赖。
     *
     * <p>该模式直接读取公开页面，不调用搜索 API，所以这里有意不检查小红书 Cookie；
     * 高德 Key 和支持图片输入的模型仍然是必需配置。
     */
    private void validateXhsNoteRuntimeSettings() {
        List<String> missing = new ArrayList<>();
        if (!runtimeSettingsService.hasText(TripstarSettingKeys.AMAP_WEB_KEY)) {
            missing.add("高德地图 Web Service Key");
        }
        if (!runtimeSettingsService.hasText(TripstarSettingKeys.OPENAI_API_KEY)) {
            missing.add("AI API Key");
        }
        if (!runtimeSettingsService.hasText(TripstarSettingKeys.OPENAI_MODEL)) {
            missing.add("支持多模态的 AI 模型名称");
        }
        if (!missing.isEmpty()) {
            String message = "缺少运行时配置：" + String.join("、", missing)
                + "。指定笔记模式不需要小红书 Cookie，请检查高德和 AI 配置。";
            log.warn("[TripTask] 指定笔记运行时配置校验失败 missing={}", missing);
            throw new BizException(message);
        }
    }

    /**
     * 异步执行指定笔记规划主流程。
     *
     * <p>前半段由 {@link XhsNoteResearchService} 读取和研究资料，后半段继续复用
     * {@link TripAiPlannerService} 中的 Planner/Review 协作。
     */
    private void runXhsNotePlanning(String taskId, XhsNotePlanRequest request) {
        long startedAt = System.currentTimeMillis();
        try {
            // 通知前端任务线程已经启动，接下来会进入更细的笔记解析阶段。
            update(
                    taskId,
                    TripTaskStatus.PROCESSING,
                    TripTaskStage.INITIALIZING,
                    TripTaskProgress.INITIALIZING,
                    TripTaskMessages.XHS_NOTE_INITIALIZING,
                    null,
                    null
            );

            XhsNoteResearchContext researchContext = xhsNoteResearchService.research(
                    taskId,
                    request,
                    // Research Service 不感知 WebSocket，只通过回调报告阶段；任务服务统一负责推送。
                    (stage, progress, message) -> update(
                            taskId,
                            TripTaskStatus.PROCESSING,
                            stage,
                            progress,
                            message,
                            null,
                            null
                    )
            );

            // 研究阶段必须同时产出笔记事实和真实高德数据，缺任意一类都不进入 Planner。
            if (!researchContext.contentContext().realData()) {
                throw new BizException("指定笔记没有生成可用的小红书内容上下文。");
            }
            if (!researchContext.mapContext().realData()) {
                throw new BizException("指定笔记没有生成可用的高德地图上下文。");
            }
            TripRequest inferredTrip = researchContext.tripRequest();

            // 从这里开始与自主规划汇合：Planner 接收相同的 TripRequest、MapContext 和 ContentContext。
            update(
                    taskId,
                    TripTaskStatus.PROCESSING,
                    TripTaskStage.PLANNING,
                    TripTaskProgress.PLANNING,
                    TripTaskMessages.PLANNING,
                    null,
                    null
            );

            TripPlanResponse response = tripAiPlannerService.planFromXhsNotes(
                    taskId,
                    inferredTrip,
                    researchContext.mapContext(),
                    researchContext.contentContext()
            ).orElseThrow(() -> new BizException("Planner Agent 未返回可解析的旅行计划。"));
            // 只在指定笔记模式复用 POI Service 已取得的高德图片；自主规划响应完全不经过这里。
            response = xhsNotePlanPhotoEnricher.enrich(response, researchContext.mapContext());

            // Planner 已经返回最终计划；保留原 graph_building 阶段以兼容现有前端和结果页流程。
            update(
                    taskId,
                    TripTaskStatus.PROCESSING,
                    TripTaskStage.GRAPH_BUILDING,
                    TripTaskProgress.GRAPH_BUILDING,
                    TripTaskMessages.GRAPH_BUILDING,
                    null,
                    null
            );

            update(
                    taskId,
                    TripTaskStatus.COMPLETED,
                    TripTaskStage.COMPLETED,
                    TripTaskProgress.DONE,
                    TripTaskMessages.COMPLETED,
                    response,
                    null
            );
            log.info("[TripTask] 指定笔记规划完成 taskId={} elapsedMs={}",
                    taskId, System.currentTimeMillis() - startedAt);
        } catch (Exception ex) {
            log.error("[TripTask] 指定笔记规划失败 taskId={} elapsedMs={} reason={}",
                    taskId, System.currentTimeMillis() - startedAt, ex.getMessage(), ex);
            // 任何阶段失败都只推送一次 failed 终态，前端据此停止等待并显示原始错误原因。
            update(
                    taskId,
                    TripTaskStatus.FAILED,
                    TripTaskStage.FAILED,
                    TripTaskProgress.DONE,
                    TripTaskMessages.FAILED,
                    null,
                    ex.getMessage()
            );
        }
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

    /** 把自主规划请求转换成任务状态可序列化的快照。 */
    private Map<String, Object> tripRequestPayload(TripRequest request) {
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

    /** 把指定笔记请求转换成快照，供状态接口和 WebSocket 初始事件返回。 */
    private Map<String, Object> xhsNoteRequestPayload(XhsNotePlanRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("share_text", request.share_text());
        payload.put("note_content", request.note_content());
        payload.put("requirement", request.requirement());
        payload.put("start_date", request.start_date());
        return payload;
    }

    private static final class TripTaskState {

        private final String taskId;
        // 使用通用 Map 后，一套任务状态既能保存 TripRequest，也能保存 XhsNotePlanRequest。
        private final Map<String, Object> requestPayload;
        private final CopyOnWriteArrayList<TripTaskSubscriber> subscribers = new CopyOnWriteArrayList<>();

        private volatile String status = TripTaskStatus.PROCESSING;
        private volatile String stage = TripTaskStage.SUBMITTED;
        private volatile int progress = 0;
        private volatile String message = "";
        private volatile String error = "";
        private volatile TripPlanResponse result;

        private TripTaskState(String taskId, Map<String, Object> requestPayload) {
            this.taskId = taskId;
            this.requestPayload = new LinkedHashMap<>(requestPayload);
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
            // 返回副本，避免外部代码修改任务内部保存的原始请求快照。
            return new LinkedHashMap<>(requestPayload);
        }
    }
}
