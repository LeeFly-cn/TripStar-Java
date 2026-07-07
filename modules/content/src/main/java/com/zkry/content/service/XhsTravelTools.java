package com.zkry.content.service;

import com.zkry.common.core.constant.TravelDataSource;
import com.zkry.common.core.constant.TravelToolResponseFields;
import com.zkry.common.core.exception.BizException;
import com.zkry.common.json.utils.JsonUtils;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * 小红书底层工具集合。
 *
 * <p>它复用 {@link XhsContentService} 和 {@link XhsNativeClient} 的真实采集能力，
 * <p>当前分阶段 workflow 默认通过 {@link XhsSearchTools} 和 {@link XhsDetailTools}
 * 暴露白名单工具；这个类只保留搜索和详情的底层实现，供包装类复用。
 */
@Component
public class XhsTravelTools {

    private static final Logger log = LoggerFactory.getLogger(XhsTravelTools.class);

    private final XhsContentService xhsContentService;
    private final XhsNativeClient xhsNativeClient;

    public XhsTravelTools(XhsContentService xhsContentService, XhsNativeClient xhsNativeClient) {
        this.xhsContentService = xhsContentService;
        this.xhsNativeClient = xhsNativeClient;
    }

    public String searchNotes(String keyword, Integer page, Integer pageSize) {
        long startedAt = System.currentTimeMillis();
        int safePage = page == null || page <= 0 ? 1 : page;
        int safePageSize = pageSize == null || pageSize <= 0 ? 5 : Math.min(pageSize, 10);
        log.info("[XHS-Tool] searchNotes keyword={} page={} pageSize={}", keyword, safePage, safePageSize);
        try {
            JsonNode root = xhsNativeClient.searchNotes(cookie(), keyword, safePage, 0, safePageSize);
            List<Map<String, Object>> notes = simplifySearch(root, safePageSize);
            log.info("[XHS-Tool] searchNotes 成功 keyword={} noteCount={} elapsedMs={}",
                keyword, notes.size(), elapsed(startedAt));
            return success(XhsToolNames.SEARCH_NOTES, notes);
        } catch (Exception ex) {
            return failure(XhsToolNames.SEARCH_NOTES, ex, startedAt);
        }
    }

    public String noteDetail(String noteId, String xsecToken, String xsecSource) {
        long startedAt = System.currentTimeMillis();
        log.info("[XHS-Tool] noteDetail noteId={} xsecSource={}", noteId, xsecSource);
        try {
            JsonNode detail = xhsNativeClient.noteDetail(cookie(), noteId, xsecToken, xsecSource);
            Map<String, Object> simplified = simplifyDetail(detail);
            String desc = String.valueOf(simplified.getOrDefault("desc", ""));
            log.info("[XHS-Tool] noteDetail 成功 noteId={} descLength={} hasPhoto={} elapsedMs={}",
                noteId,
                desc.length(),
                String.valueOf(simplified.getOrDefault("first_photo", "")).length() > 0,
                elapsed(startedAt));
            return success(XhsToolNames.NOTE_DETAIL, simplified);
        } catch (Exception ex) {
            return failure(XhsToolNames.NOTE_DETAIL, ex, startedAt);
        }
    }

    private String cookie() {
        return xhsContentService.cookie()
            .orElseThrow(() -> new BizException("小红书 Cookie 未配置，请先在设置页填写“小红书 Cookie”。"));
    }

    private List<Map<String, Object>> simplifySearch(JsonNode root, int limit) {
        JsonNode items = root.path("data").path("items");
        if (!items.isArray()) {
            return List.of();
        }
        java.util.ArrayList<Map<String, Object>> notes = new java.util.ArrayList<>();
        for (JsonNode item : items) {
            if (notes.size() >= limit) {
                break;
            }
            if (!"note".equals(item.path("model_type").asText(""))) {
                continue;
            }
            JsonNode noteCard = item.path("note_card");
            String noteId = XhsNoteJsons.noteId(item);
            if (noteId.isBlank()) {
                log.debug("[XHS-Tool] 跳过无 noteId 的搜索结果 rawId={} title={}",
                    item.path("id").asText(""), XhsNoteJsons.title(noteCard));
                continue;
            }
            Map<String, Object> note = new LinkedHashMap<>();
            note.put("note_id", noteId);
            note.put("xsec_token", XhsNoteJsons.xsecToken(item));
            note.put("xsec_source", "pc_search");
            note.put("title", XhsNoteJsons.title(noteCard));
            note.put("liked_count", noteCard.path("interact_info").path("liked_count").asText(""));
            notes.add(note);
        }
        return notes;
    }

    private Map<String, Object> simplifyDetail(JsonNode detail) {
        JsonNode items = detail.path("data").path("items");
        Map<String, Object> body = new LinkedHashMap<>();
        if (!items.isArray() || items.isEmpty()) {
            return body;
        }
        JsonNode noteCard = items.get(0).path("note_card");
        body.put("title", noteCard.path("title").asText(noteCard.path("display_title").asText("")));
        body.put("desc", noteCard.path("desc").asText(""));
        body.put("first_photo", firstPhoto(noteCard.path("image_list")));
        return body;
    }

    private String firstPhoto(JsonNode imageList) {
        if (!imageList.isArray() || imageList.isEmpty()) {
            return "";
        }
        JsonNode first = imageList.get(0);
        JsonNode infoList = first.path("info_list");
        if (infoList.isArray() && !infoList.isEmpty()) {
            return infoList.get(0).path("url").asText("");
        }
        return first.path("url_default").asText(first.path("url_pre").asText(""));
    }

    private String success(String tool, Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(TravelToolResponseFields.SUCCESS, true);
        body.put(TravelToolResponseFields.SOURCE, TravelDataSource.XHS);
        body.put(TravelToolResponseFields.TOOL, tool);
        body.put(TravelToolResponseFields.DATA, data);
        return JsonUtils.toJsonString(body);
    }

    private String failure(String tool, Exception ex, long startedAt) {
        log.warn("[XHS-Tool] 调用失败 tool={} elapsedMs={} reason={}",
            tool, System.currentTimeMillis() - startedAt, ex.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(TravelToolResponseFields.SUCCESS, false);
        body.put(TravelToolResponseFields.SOURCE, TravelDataSource.XHS);
        body.put(TravelToolResponseFields.TOOL, tool);
        body.put(TravelToolResponseFields.ERROR, ex.getMessage());
        return JsonUtils.toJsonString(body);
    }

    private long elapsed(long startedAt) {
        return System.currentTimeMillis() - startedAt;
    }
}
