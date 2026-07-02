package com.zkry.content.service;

import com.zkry.common.json.utils.JsonUtils;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

final class XhsCookieUtils {

    private XhsCookieUtils() {
    }

    static String normalize(String cookie) {
        if (cookie == null || cookie.isBlank()) {
            return "";
        }
        String normalized = cookie.trim();
        if (normalized.length() >= 2
            && normalized.charAt(0) == normalized.charAt(normalized.length() - 1)
            && (normalized.charAt(0) == '\'' || normalized.charAt(0) == '"')) {
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        }

        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            String converted = fromJsonCookieList(normalized);
            return converted.isBlank() ? normalized : converted;
        }
        if (normalized.startsWith("{") && normalized.contains("\"name\"") && normalized.contains("\"value\"")) {
            String converted = fromJsonCookieList("[" + normalized + "]");
            return converted.isBlank() ? normalized : converted;
        }
        return normalized;
    }

    private static String fromJsonCookieList(String json) {
        try {
            JsonNode root = JsonUtils.parseTree(json);
            if (root == null || !root.isArray()) {
                return "";
            }
            List<String> pairs = new ArrayList<>();
            for (JsonNode item : root) {
                String name = text(item.path("name"));
                String value = text(item.path("value"));
                if (!name.isBlank()) {
                    pairs.add(name + "=" + value);
                }
            }
            return String.join("; ", pairs);
        } catch (Exception ex) {
            return "";
        }
    }

    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        return node.asText("");
    }
}
