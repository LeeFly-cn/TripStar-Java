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
        @Value("${tripstar.content.xhs.mode:service}") String xhsMode,
        @Value("${spring.ai.dashscope.api-key:}") String dashscopeApiKey,
        @Value("${spring.ai.dashscope.chat.options.model:qwen-plus}") String dashscopeModel
    ) {
        settings.put(TripstarSettingKeys.AMAP_WEB_KEY, emptyToDefault(amapWebKey));
        settings.put(TripstarSettingKeys.AMAP_WEB_JS_KEY, "");
        settings.put(TripstarSettingKeys.GOOGLE_MAPS_API_KEY, "");
        settings.put(TripstarSettingKeys.GOOGLE_MAPS_PROXY, "");
        settings.put(TripstarSettingKeys.XHS_COOKIE, emptyToDefault(xhsCookie));
        settings.put(TripstarSettingKeys.XHS_MODE, emptyToDefault(xhsMode));
        settings.put(TripstarSettingKeys.OPENAI_API_KEY, emptyToDefault(dashscopeApiKey));
        settings.put(TripstarSettingKeys.OPENAI_BASE_URL, "");
        settings.put(TripstarSettingKeys.OPENAI_MODEL, emptyToDefault(dashscopeModel));
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
