package com.zkry.content.service;

import tools.jackson.databind.JsonNode;

/**
 * 小红书笔记 JSON 字段读取工具。
 *
 * <p>小红书 Web 接口字段会随前端版本微调，例如 note id 可能出现在
 * {@code item.id}、{@code note_card.note_id} 或驼峰字段里。把这些兼容逻辑集中在
 * 这里，service 模式和 tool 模式就不会各写一套易漂移的字段路径。
 */
final class XhsNoteJsons {

    private XhsNoteJsons() {
    }

    /** 兼容搜索结果中多种 noteId 字段路径。 */
    static String noteId(JsonNode item) {
        JsonNode noteCard = item == null ? null : item.path("note_card");
        return firstText(
            item == null ? null : item.path("id"),
            item == null ? null : item.path("note_id"),
            item == null ? null : item.path("noteId"),
            noteCard == null ? null : noteCard.path("note_id"),
            noteCard == null ? null : noteCard.path("noteId"),
            noteCard == null ? null : noteCard.path("id")
        );
    }

    /** 兼容搜索结果中多种 xsecToken 字段路径。 */
    static String xsecToken(JsonNode item) {
        JsonNode noteCard = item == null ? null : item.path("note_card");
        return firstText(
            item == null ? null : item.path("xsec_token"),
            item == null ? null : item.path("xsecToken"),
            noteCard == null ? null : noteCard.path("xsec_token"),
            noteCard == null ? null : noteCard.path("xsecToken")
        );
    }

    /** 兼容卡片标题在不同 Web 版本中的字段名。 */
    static String title(JsonNode noteCard) {
        return firstText(
            noteCard == null ? null : noteCard.path("display_title"),
            noteCard == null ? null : noteCard.path("displayTitle"),
            noteCard == null ? null : noteCard.path("title")
        );
    }

    /** 返回候选 JSON 节点中第一个非空文本值。 */
    private static String firstText(JsonNode... nodes) {
        if (nodes == null) {
            return "";
        }
        for (JsonNode node : nodes) {
            if (node == null || node.isMissingNode() || node.isNull()) {
                continue;
            }
            String value = node.asText("");
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
