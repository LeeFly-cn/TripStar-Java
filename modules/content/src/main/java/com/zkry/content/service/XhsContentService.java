package com.zkry.content.service;

import com.zkry.ai.service.AiTextService;
import com.zkry.common.core.config.TripstarRuntimeSettingsService;
import com.zkry.common.core.exception.BizException;
import com.zkry.ai.service.LlmJsonExtractor;
import com.zkry.common.json.utils.JsonUtils;
import com.zkry.content.dto.ContentAttractionCandidate;
import com.zkry.content.dto.ContentCityContext;
import com.zkry.content.dto.ContentCityRequest;
import com.zkry.content.dto.ContentPlanningContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
public class XhsContentService implements TravelContentService {

    private static final Logger log = LoggerFactory.getLogger(XhsContentService.class);

    private final XhsNativeClient xhsNativeClient;
    private final AiTextService aiTextService;
    private final TripstarRuntimeSettingsService runtimeSettingsService;

    @Value("${tripstar.content.xhs.enabled:true}")
    private boolean enabled;

    @Value("${tripstar.content.xhs.max-notes:4}")
    private int maxNotes;

    public XhsContentService(
        XhsNativeClient xhsNativeClient,
        AiTextService aiTextService,
        TripstarRuntimeSettingsService runtimeSettingsService
    ) {
        this.xhsNativeClient = xhsNativeClient;
        this.aiTextService = aiTextService;
        this.runtimeSettingsService = runtimeSettingsService;
    }

    @Override
    public ContentPlanningContext collect(List<ContentCityRequest> requests) {
        if (!enabled) {
            log.warn("[XHS] 小红书内容源未启用");
            throw new BizException("小红书内容源未启用，请检查 tripstar.content.xhs.enabled 配置。");
        }
        Optional<String> cookie = cookie();
        if (cookie.isEmpty()) {
            log.warn("[XHS] 未配置小红书 Cookie");
            throw new BizException("小红书 Cookie 未配置，请先在设置页填写“小红书 Cookie”。");
        }
        if (requests == null || requests.isEmpty()) {
            log.info("[XHS] 城市请求为空，跳过游记搜索");
            return ContentPlanningContext.empty("xhs", "没有城市信息，跳过小红书搜索。");
        }

        log.info("[XHS] 开始采集小红书游记 cityCount={} maxNotesPerCity={}", requests.size(), maxNotes);
        List<ContentCityContext> cityContexts = new ArrayList<>();
        for (ContentCityRequest request : requests) {
            try {
                cityContexts.add(collectCity(cookie.get(), request));
            } catch (XhsCookieExpiredException ex) {
                log.warn("[XHS] 小红书采集失败 city={} reason={}", request.city(), ex.getMessage());
                cityContexts.add(new ContentCityContext(
                    request.city(),
                    request.keyword(),
                    "xhs",
                    "",
                    List.of(),
                    ex.getMessage()
                ));
            } catch (Exception ex) {
                log.warn("[XHS] 小红书采集异常 city={} reason={}", request.city(), ex.getMessage());
            }
        }

        boolean hasData = cityContexts.stream().anyMatch(ContentCityContext::hasAnyData);
        log.info("[XHS] 小红书游记采集结束 realData={} cityContexts={} extractedCandidates={}",
            hasData,
            cityContexts.size(),
            cityContexts.stream().mapToInt(context -> context.safeAttractions().size()).sum());
        String message = hasData
            ? "已采集小红书游记内容。"
            : failureMessage(cityContexts);
        return new ContentPlanningContext(
            cityContexts,
            hasData,
            "xhs",
            message
        );
    }

    @Override
    public String photo(String keyword) {
        if (!enabled) {
            log.warn("[XHS] 图片搜索失败，小红书内容源未启用");
            throw new BizException("小红书内容源未启用，请检查 tripstar.content.xhs.enabled 配置。");
        }
        if (keyword == null || keyword.isBlank()) {
            log.warn("[XHS] 图片搜索失败，关键词为空");
            throw new BizException("图片搜索关键词为空。");
        }
        Optional<String> cookie = cookie();
        if (cookie.isEmpty()) {
            log.warn("[XHS] 图片搜索失败，未配置小红书 Cookie keyword={}", keyword);
            throw new BizException("小红书 Cookie 未配置，请先在设置页填写“小红书 Cookie”。");
        }
        long startedAt = System.currentTimeMillis();
        log.info("[XHS] 开始搜索景点图片 keyword={}", keyword);
        try {
            JsonNode search = xhsNativeClient.searchNotes(cookie.get(), keyword, 1, 0, 20);
            JsonNode items = search.path("data").path("items");
            if (!items.isArray()) {
                log.info("[XHS] 图片搜索结果不是数组 keyword={} elapsedMs={}", keyword, System.currentTimeMillis() - startedAt);
                return "";
            }
            log.info("[XHS] 图片搜索返回笔记候选 keyword={} count={}", keyword, items.size());
            for (JsonNode item : items) {
                if (!"note".equals(item.path("model_type").asText(""))) {
                    continue;
                }
                String noteId = item.path("id").asText("");
                String xsecToken = item.path("xsec_token").asText("");
                if (noteId.isBlank()) {
                    continue;
                }
                JsonNode detail = xhsNativeClient.noteDetail(cookie.get(), noteId, xsecToken, "pc_search");
                String photoUrl = firstPhoto(detail);
                if (!photoUrl.isBlank()) {
                    log.info("[XHS] 图片搜索成功 keyword={} noteId={} elapsedMs={}",
                        keyword, noteId, System.currentTimeMillis() - startedAt);
                    return photoUrl;
                }
            }
        } catch (Exception ex) {
            log.warn("[XHS] 小红书图片搜索失败 keyword={} reason={}", keyword, ex.getMessage());
        }
        log.info("[XHS] 图片搜索未找到可用图片 keyword={} elapsedMs={}", keyword, System.currentTimeMillis() - startedAt);
        return "";
    }

    /**
     * 小红书单城市采集流程：
     * 1. 搜索旅游攻略笔记；
     * 2. 拉取笔记详情正文；
     * 3. 合并正文交给 LLM 提炼景点候选。
     */
    private ContentCityContext collectCity(String cookie, ContentCityRequest request) {
        String query = request.city() + " " + request.keyword() + " 旅游 景点攻略";
        long startedAt = System.currentTimeMillis();
        log.info("[XHS] 开始搜索城市游记 city={} keyword={} query={}", request.city(), request.keyword(), query);
        JsonNode search = xhsNativeClient.searchNotes(cookie, query, 1, 0, 20);
        JsonNode items = search.path("data").path("items");
        StringBuilder combined = new StringBuilder();
        int count = 0;
        if (items.isArray()) {
            log.info("[XHS] 搜索返回候选笔记 city={} totalItems={}", request.city(), items.size());
            for (JsonNode item : items) {
                if (count >= maxNotes) {
                    break;
                }
                if (!"note".equals(item.path("model_type").asText(""))) {
                    continue;
                }
                String noteId = item.path("id").asText("");
                String xsecToken = item.path("xsec_token").asText("");
                JsonNode noteCard = item.path("note_card");
                String title = noteCard.path("display_title").asText("");
                String desc = "";
                if (!noteId.isBlank()) {
                    try {
                        log.debug("[XHS] 获取笔记详情 city={} noteId={} title={}", request.city(), noteId, truncate(title, 40));
                        desc = noteDescription(xhsNativeClient.noteDetail(cookie, noteId, xsecToken, "pc_search"));
                    } catch (Exception ex) {
                        log.debug("[XHS] 小红书详情获取失败 noteId={} reason={}", noteId, ex.getMessage());
                    }
                }
                if (!title.isBlank() || !desc.isBlank()) {
                    count++;
                    log.info("[XHS] 采集到游记正文 city={} index={} noteId={} title={} descLength={}",
                        request.city(), count, noteId, truncate(title, 50), desc.length());
                    combined.append("\n笔记").append(count).append(":\n标题: ")
                        .append(title).append("\n正文内容: ").append(desc).append("\n");
                }
            }
        }

        String rawText = combined.toString().trim();
        if (rawText.isBlank()) {
            log.info("[XHS] 城市游记为空 city={} elapsedMs={}", request.city(), System.currentTimeMillis() - startedAt);
            return new ContentCityContext(
                request.city(),
                request.keyword(),
                "xhs",
                "",
                List.of(),
                "未在小红书检索到关于 " + request.city() + " " + request.keyword() + " 的内容。"
            );
        }

        List<ContentAttractionCandidate> candidates = extractAttractions(request, rawText);
        log.info("[XHS] 城市游记采集完成 city={} noteCount={} rawLength={} extractedCandidates={} elapsedMs={}",
            request.city(), count, rawText.length(), candidates.size(), System.currentTimeMillis() - startedAt);
        String message = candidates.isEmpty()
            ? "已采集小红书游记原文，LLM 提炼未启用或未成功。"
            : "已从小红书游记中提炼景点候选。";
        return new ContentCityContext(request.city(), request.keyword(), "xhs", rawText, candidates, message);
    }

    private List<ContentAttractionCandidate> extractAttractions(ContentCityRequest request, String rawText) {
        log.info("[XHS] 开始 LLM 提炼小红书景点 city={} rawLength={} aiAvailable={}",
            request.city(), rawText.length(), aiTextService.isAvailable());
        Optional<String> response = aiTextService.generate(
            "你是 TripStar 的游记内容提炼智能体。你只输出合法 JSON 数组，不输出 markdown 或解释。",
            extractionPrompt(request, rawText)
        );
        if (response.isEmpty()) {
            log.info("[XHS] LLM 未返回提炼结果 city={}", request.city());
            return List.of();
        }
        List<String> candidatesJson = LlmJsonExtractor.extractJsonArrayCandidates(response.get());
        log.info("[XHS] LLM 提炼返回 city={} responseLength={} jsonCandidates={}",
            request.city(), response.get().length(), candidatesJson.size());
        for (String json : candidatesJson) {
            try {
                List<ContentAttractionCandidate> candidates = JsonUtils.parseArray(json, ContentAttractionCandidate.class);
                log.info("[XHS] LLM 提炼解析成功 city={} candidateCount={}", request.city(), candidates.size());
                return candidates;
            } catch (Exception ex) {
                log.debug("[XHS] 小红书景点提炼 JSON 候选解析失败 reason={}", ex.getMessage());
            }
        }
        log.warn("[XHS] LLM 提炼 JSON 解析失败 city={}", request.city());
        return List.of();
    }

    private String extractionPrompt(ContentCityRequest request, String rawText) {
        return """
            请从以下真实小红书旅游游记中提炼游玩景点，输出严格 JSON 数组。

            城市：%s
            用户偏好关键词：%s
            输出语言：%s

            每个对象必须包含：
            - name：用于前端展示的景点名，按输出语言填写
            - name_zh：中文简体官方名
            - name_en：英文官方名
            - reason：小红书用户真实评价、避坑建议或打卡理由
            - duration：建议游玩时长，数字，单位分钟
            - reservation_required：是否需要预约，布尔值
            - reservation_tips：预约渠道、提前天数、限流提醒等，没有则空字符串

            只提炼真实景点，不要提炼泛泛的城市、酒店、商场广告。

            游记内容：
            %s
            """.formatted(request.city(), request.keyword(), request.safeLanguage(), rawText);
    }

    private String noteDescription(JsonNode detail) {
        JsonNode items = detail.path("data").path("items");
        if (!items.isArray() || items.isEmpty()) {
            return "";
        }
        return items.get(0).path("note_card").path("desc").asText("");
    }

    private String firstPhoto(JsonNode detail) {
        JsonNode items = detail.path("data").path("items");
        if (!items.isArray() || items.isEmpty()) {
            return "";
        }
        JsonNode imageList = items.get(0).path("note_card").path("image_list");
        if (!imageList.isArray() || imageList.isEmpty()) {
            return "";
        }
        JsonNode first = imageList.get(0);
        JsonNode infoList = first.path("info_list");
        if (infoList.isArray() && !infoList.isEmpty()) {
            if (infoList.size() > 1) {
                String url = infoList.get(1).path("url").asText("");
                if (!url.isBlank()) {
                    return url;
                }
            }
            String url = infoList.get(0).path("url").asText("");
            if (!url.isBlank()) {
                return url;
            }
        }
        return first.path("url_default").asText(first.path("url_pre").asText(first.path("url").asText("")));
    }

    private Optional<String> cookie() {
        return runtimeSettingsService.stringValue("xhs_cookie")
            .map(XhsCookieUtils::normalize)
            .filter(value -> !value.isBlank());
    }

    private String failureMessage(List<ContentCityContext> cityContexts) {
        if (cityContexts == null || cityContexts.isEmpty()) {
            return "小红书未返回有效游记内容。";
        }
        String detail = cityContexts.stream()
            .map(context -> context.city() + "：" + context.message())
            .filter(value -> value != null && !value.isBlank())
            .collect(Collectors.joining("；"));
        return detail.isBlank() ? "小红书未返回有效游记内容。" : detail;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }
}
