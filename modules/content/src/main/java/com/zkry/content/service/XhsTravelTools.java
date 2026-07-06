package com.zkry.content.service;

import com.zkry.common.core.constant.TravelDataSource;
import com.zkry.common.core.constant.TravelToolResponseFields;
import com.zkry.common.core.exception.BizException;
import com.zkry.common.json.utils.JsonUtils;
import com.zkry.content.dto.ContentCityContext;
import com.zkry.content.dto.ContentCityRequest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * 小红书工具集合，供 TravelResearchAgent 主动调用。
 *
 * <p>它复用 {@link XhsContentService} 和 {@link XhsNativeClient} 的真实采集能力，
 * 让 Agent 可以选择“一次性采集城市上下文”或“先搜索笔记、再拉详情”。这正好用于学习
 * service 编排和 agent tool 编排的差异。
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

    @Tool(name = XhsToolNames.COLLECT_CITY_CONTEXT, description = "采集某城市的小红书真实游记正文，并提炼景点、预约、避坑和推荐理由。")
    public String collectCityContext(
        @ToolParam(description = "城市名，例如：昆明。", required = true) String city,
        @ToolParam(description = "该城市停留天数。", required = false) Integer days,
        @ToolParam(description = "用户偏好，多个偏好可用逗号分隔。", required = false) String preferences,
        @ToolParam(description = "输出语言，例如 zh/en/ja。", required = false) String language
    ) {
        long startedAt = System.currentTimeMillis();
        log.info("[XHS-Tool] collectCityContext city={} days={} preferences={} language={}",
            city, days, preferences, language);
        try {
            String cookie = cookie();
            ContentCityRequest request = new ContentCityRequest(
                city,
                days == null || days <= 0 ? 1 : days,
                split(preferences),
                language
            );
            ContentCityContext context = xhsContentService.collectCity(cookie, request);
            log.info("[XHS-Tool] collectCityContext 成功 city={} rawLength={} candidates={} elapsedMs={}",
                city,
                context.rawText() == null ? 0 : context.rawText().length(),
                context.safeAttractions().size(),
                elapsed(startedAt));
            return success(XhsToolNames.COLLECT_CITY_CONTEXT, context);
        } catch (Exception ex) {
            return failure(XhsToolNames.COLLECT_CITY_CONTEXT, ex, startedAt);
        }
    }

    @Tool(name = XhsToolNames.SEARCH_NOTES, description = "搜索小红书笔记，返回笔记 id、标题和 xsec_token 等基础信息，适合 Agent 决定是否继续拉详情。")
    public String searchNotes(
        @ToolParam(description = "搜索关键词，例如：昆明 老人 轻松 景点攻略。", required = true) String keyword,
        @ToolParam(description = "页码，从 1 开始。", required = false) Integer page,
        @ToolParam(description = "最多返回数量，建议 3 到 10。", required = false) Integer pageSize
    ) {
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

    @Tool(name = XhsToolNames.NOTE_DETAIL, description = "获取小红书笔记详情正文和首图，用于理解真实游记内容。")
    public String noteDetail(
        @ToolParam(description = "小红书笔记 id。", required = true) String noteId,
        @ToolParam(description = "搜索结果里的 xsec_token。", required = false) String xsecToken,
        @ToolParam(description = "xsec_source，默认 pc_search。", required = false) String xsecSource
    ) {
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
            Map<String, Object> note = new LinkedHashMap<>();
            note.put("note_id", item.path("id").asText(""));
            note.put("xsec_token", item.path("xsec_token").asText(""));
            note.put("title", noteCard.path("display_title").asText(""));
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

    private List<String> split(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return Arrays.stream(text.split("[,，、;；\\s]+"))
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .toList();
    }

    private long elapsed(long startedAt) {
        return System.currentTimeMillis() - startedAt;
    }
}
