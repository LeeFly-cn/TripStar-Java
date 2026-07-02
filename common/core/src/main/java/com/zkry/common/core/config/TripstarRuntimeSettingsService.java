package com.zkry.common.core.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class TripstarRuntimeSettingsService {

    private final Map<String, Object> settings = new LinkedHashMap<>();

    public TripstarRuntimeSettingsService(
        @Value("${tripstar.map.amap.key:}") String amapWebKey,
        @Value("${tripstar.content.xhs.cookie:}") String xhsCookie,
        @Value("${spring.ai.dashscope.api-key:}") String dashscopeApiKey,
        @Value("${spring.ai.dashscope.chat.options.model:qwen-plus}") String dashscopeModel
    ) {
        settings.put("vite_amap_web_key", emptyToDefault(amapWebKey));
        settings.put("vite_amap_web_js_key", "");
        settings.put("google_maps_api_key", "");
        settings.put("google_maps_proxy", "");
        settings.put("xhs_cookie", emptyToDefault(xhsCookie));
        settings.put("openai_api_key", emptyToDefault(dashscopeApiKey));
        settings.put("openai_base_url", "");
        settings.put("openai_model", emptyToDefault(dashscopeModel));
    }

    public synchronized Map<String, Object> snapshot() {
        return new LinkedHashMap<>(settings);
    }

    public synchronized void update(Map<String, Object> updates) {
        if (updates == null) {
            return;
        }
        settings.putAll(updates);
    }

    public synchronized Optional<String> stringValue(String key) {
        Object value = settings.get(key);
        if (value == null) {
            return Optional.empty();
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? Optional.empty() : Optional.of(text);
    }

    public synchronized boolean hasText(String key) {
        return stringValue(key).isPresent();
    }

    private String emptyToDefault(String value) {
        return value == null ? "" : value;
    }
}
