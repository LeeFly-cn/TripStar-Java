package com.zkry.api.trip;

import com.zkry.trip.dto.SubmitTripPlanResponse;
import com.zkry.trip.dto.TripHistoryItem;
import com.zkry.trip.dto.TripRequest;
import com.zkry.trip.service.TripTaskService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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

    private final TripTaskService tripTaskService;

    public TripController(TripTaskService tripTaskService) {
        this.tripTaskService = tripTaskService;
    }

    @PostMapping("/plan")
    public SubmitTripPlanResponse plan(@RequestBody TripRequest request) {
        return tripTaskService.submit(request);
    }

    @GetMapping("/status/{taskId}")
    public Map<String, Object> status(@PathVariable String taskId) {
        return tripTaskService.status(taskId);
    }

    @GetMapping("/history")
    public Map<String, Object> history(@RequestParam(defaultValue = "8") int limit) {
        TripHistoryItem item = new TripHistoryItem(
            "java-demo",
            "java-demo",
            "Java 学习版",
            LocalDateTime.now().toLocalDate().toString(),
            LocalDateTime.now().toLocalDate().plusDays(2).toString(),
            3,
            LocalDateTime.now().toString(),
            "当前为 Java 后端 mock 历史记录，用于兼容现有 Vue 首页。"
        );
        return Map.of("items", limit <= 0 ? List.of() : List.of(item));
    }
}
