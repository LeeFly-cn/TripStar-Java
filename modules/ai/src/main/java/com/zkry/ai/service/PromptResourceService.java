package com.zkry.ai.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * Prompt 模板加载和变量替换服务。
 *
 * <p>所有较长提示词都放在 {@code src/main/resources/prompts/tripstar} 下，
 * Java 代码只传变量。这样你调 Agent 行为时优先改 prompt 文件，不需要重新理解
 * 大段硬编码字符串。
 */
@Service
public class PromptResourceService {

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /**
     * 读取 classpath 下的 prompt 模板，并做内存缓存。
     */
    public String load(String path) {
        return cache.computeIfAbsent(path, this::read);
    }

    /**
     * 渲染模板变量。
     *
     * <p>当前使用轻量的 {@code {{name}}} 替换，足够覆盖 TripStar 的 prompt 场景。
     */
    public String render(String path, Map<String, String> variables) {
        String template = load(path);
        if (variables == null || variables.isEmpty()) {
            return template;
        }
        String rendered = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            rendered = rendered.replace("{{" + entry.getKey() + "}}", entry.getValue() == null ? "" : entry.getValue());
        }
        return rendered;
    }

    private String read(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Prompt resource not found: " + path, ex);
        }
    }
}
