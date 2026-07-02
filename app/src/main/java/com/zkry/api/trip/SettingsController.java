package com.zkry.api.trip;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final Map<String, Object> runtimeSettings = new LinkedHashMap<>();

    public SettingsController() {
        runtimeSettings.put("vite_amap_web_key", "");
        runtimeSettings.put("vite_amap_web_js_key", "");
        runtimeSettings.put("google_maps_api_key", "");
        runtimeSettings.put("google_maps_proxy", "");
        runtimeSettings.put("xhs_cookie", "");
        runtimeSettings.put("openai_api_key", "");
        runtimeSettings.put("openai_base_url", "");
        runtimeSettings.put("openai_model", "");
    }

    @GetMapping
    public Map<String, Object> get() {
        return response("配置读取成功");
    }

    @PutMapping
    public Map<String, Object> save(@RequestBody Map<String, Object> updates) {
        if (updates != null) {
            runtimeSettings.putAll(updates);
        }
        return response("配置已保存到 Java mock 运行时内存");
    }

    private Map<String, Object> response(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("message", message);
        body.put("data", new LinkedHashMap<>(runtimeSettings));
        return body;
    }
}
