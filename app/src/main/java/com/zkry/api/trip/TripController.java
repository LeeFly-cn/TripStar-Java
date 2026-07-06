package com.zkry.api.trip;

import com.zkry.trip.dto.SubmitTripPlanResponse;
import com.zkry.trip.dto.TripRequest;
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

@RestController
@RequestMapping("/api/trip")
public class TripController {

    private static final Logger log = LoggerFactory.getLogger(TripController.class);

    private final TripTaskService tripTaskService;

    public TripController(TripTaskService tripTaskService) {
        this.tripTaskService = tripTaskService;
    }

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
