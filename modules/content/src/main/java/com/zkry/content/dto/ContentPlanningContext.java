package com.zkry.content.dto;

import java.util.List;
import java.util.Optional;

/**
 * 小红书游记上下文。
 *
 * <p>PlannerAgent 不直接读取原始接口响应，而是读取这个经过整理的结构。这样既能
 * 保留真实笔记正文提炼出的候选景点，也能在失败时把 source/message 明确传回主流程。
 */
public record ContentPlanningContext(
    List<ContentCityContext> cities,
    boolean realData,
    String source,
    String message
) {
    public static ContentPlanningContext empty(String source, String message) {
        return new ContentPlanningContext(List.of(), false, source, message);
    }

    public List<ContentCityContext> safeCities() {
        return cities == null ? List.of() : cities;
    }

    public Optional<ContentCityContext> findCity(String city) {
        if (city == null || city.isBlank()) {
            return Optional.empty();
        }
        return safeCities().stream()
            .filter(context -> city.equals(context.city()))
            .findFirst();
    }
}
