package com.zkry.api.trip;

import com.zkry.trip.dto.SubmitTripPlanResponse;
import com.zkry.trip.dto.TripRequest;
import com.zkry.trip.dto.xhsnote.XhsNotePlanRequest;
import com.zkry.trip.service.TripTaskService;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 旅行规划 HTTP 入口。
 *
 * <p>{@code /plan} 接收传统自主规划参数，{@code /plan/xhs-notes} 接收指定笔记材料；
 * 两个入口都会创建异步任务，并复用同一套状态查询和 WebSocket 协议。
 */
@RestController
@RequestMapping("/api/trip")
public class TripController {

    private static final Logger log = LoggerFactory.getLogger(TripController.class);

    private final TripTaskService tripTaskService;

    public TripController(TripTaskService tripTaskService) {
        this.tripTaskService = tripTaskService;
    }

    /**
     * 自主规划入口：城市、天数等参数由用户填写，继续进入现有多 Agent Graph 流程。
     */
    @PostMapping("/plan")
    public SubmitTripPlanResponse plan(@RequestBody TripRequest request) {
        log.info("[TripAPI] 收到行程规划请求 city={} cities={} days={} date={}~{} preferences={}",
            request.primaryCity(),
            request.normalizedCities().stream().map(city -> city.city() + ":" + city.safeDays() + "天").toList(),
            request.safeTravelDays(),
            safe(request.start_date()),
            safe(request.end_date()),
            request.safePreferences());
        SubmitTripPlanResponse response = tripTaskService.submit(request);
        log.info("[TripAPI] 行程规划任务已提交 taskId={} wsUrl={}", response.task_id(), response.ws_url());
        return response;
    }

    /**
     * 指定笔记规划入口：前端只提交笔记材料、额外要求和出发日期。
     *
     * <p>城市、天数、交通和住宿偏好会在后端通过多模态模型从笔记中推导。公开笔记读取
     * 不依赖搜索接口，因此该模式无需小红书 Cookie。
     */
    @PostMapping("/plan/xhs-notes")
    public SubmitTripPlanResponse planFromXhsNotes(@RequestBody XhsNotePlanRequest request) {
        log.info("[TripAPI] 收到指定笔记规划请求 startDate={} shareTextLength={} noteContentLength={} requirementLength={}",
                request == null ? "-" : request.safeStartDate(),
                request == null ? 0 : request.safeShareText().length(),
                request == null ? 0 : request.safeNoteContent().length(),
                request == null ? 0 : request.safeRequirement().length());
        // Controller 立即返回 taskId；耗时的笔记读取与 Agent 调用在线程池中异步执行。
        return tripTaskService.submitXhsNote(request);
    }

    @GetMapping("/status/{taskId}")
    public Map<String, Object> status(@PathVariable String taskId) {
        Map<String, Object> payload = tripTaskService.status(taskId);
        log.info("[TripAPI] 查询任务状态 taskId={} status={} stage={} progress={}",
            taskId, payload.get("status"), payload.getOrDefault("stage", "-"), payload.getOrDefault("progress", "-"));
        return payload;
    }

    @GetMapping("/history")
    public Map<String, Object> history(@RequestParam(defaultValue = "8") int limit) {
        log.info("[TripAPI] 查询历史行程 limit={} result=empty reason=noPersistenceYet", limit);
        return Map.of("items", List.of());
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
