package com.zkry.api.trip;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
            "status", "healthy",
            "service", "tripstar-java",
            "mode", "real-config-required"
        );
    }
}
