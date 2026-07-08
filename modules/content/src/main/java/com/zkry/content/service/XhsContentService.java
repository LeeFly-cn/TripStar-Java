package com.zkry.content.service;

import com.zkry.ai.service.AiAgentService;
import com.zkry.ai.agent.TripstarAgent;
import com.zkry.ai.prompt.TripstarPrompt;
import com.zkry.ai.prompt.TripstarPromptVariable;
import com.zkry.ai.service.AiStructuredOutputService;
import com.zkry.common.core.config.TripstarRuntimeSettingsService;
import com.zkry.common.core.config.TripstarSettingKeys;
import com.zkry.common.core.constant.TravelDataSource;
import com.zkry.common.core.exception.BizException;
import com.zkry.ai.service.PromptResourceService;
import com.zkry.content.dto.ContentAttractionCandidate;
import com.zkry.content.dto.ContentCityContext;
import com.zkry.content.dto.ContentCityRequest;
import com.zkry.content.dto.ContentPlanningContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 小红书 service-first 采集实现。
 *
 * <p>这条链路对标 Python 版 TripStar：Java 先确定性地搜索笔记、拉详情、拼接正文，
 * 再把真实游记文本交给 XhsExtractionAgent 做结构化提炼。它和 {@link XhsTravelTools}
 * 的区别是：这里由 Java 主流程主动采集；Tool 类则把同样能力暴露给 ReactAgent 主动调用。
 */
@Service
public class XhsContentService implements TravelContentService {

    private static final Logger log = LoggerFactory.getLogger(XhsContentService.class);
    private static final String XSEC_SOURCE_PC_SEARCH = "pc_search";

    private final XhsNativeClient xhsNativeClient;
    private final AiAgentService aiAgentService;
    private final AiStructuredOutputService structuredOutputService;
    private final PromptResourceService promptResourceService;
    private final TripstarRuntimeSettingsService runtimeSettingsService;

    @Value("${tripstar.content.xhs.enabled:true}")
    private boolean enabled;

    @Value("${tripstar.content.xhs.max-notes:4}")
    private int maxNotes;

    public XhsContentService(
        XhsNativeClient xhsNativeClient,
        AiAgentService aiAgentService,
        AiStructuredOutputService structuredOutputService,
        PromptResourceService promptResourceService,
        TripstarRuntimeSettingsService runtimeSettingsService
    ) {
        this.xhsNativeClient = xhsNativeClient;
        this.aiAgentService = aiAgentService;
        this.structuredOutputService = structuredOutputService;
        this.promptResourceService = promptResourceService;
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
            log.warn("[XHS] 城市请求为空，无法采集小红书游记");
            throw new BizException("城市请求为空，无法采集小红书游记。");
        }

        log.info("[XHS] 开始采集小红书游记 cityCount={} maxNotesPerCity={}", requests.size(), maxNotes);
        List<ContentCityContext> cityContexts = new ArrayList<>();
        for (ContentCityRequest request : requests) {
            cityContexts.add(collectCity(cookie.get(), request));
        }

        boolean hasData = cityContexts.size() == requests.size()
            && cityContexts.stream().allMatch(ContentCityContext::hasAnyData);
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
            TravelDataSource.XHS,
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
        JsonNode search = xhsNativeClient.searchNotes(cookie.get(), keyword, 1, 0, XhsApiDefaults.SEARCH_PAGE_SIZE);
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
            String noteId = XhsNoteJsons.noteId(item);
            String xsecToken = XhsNoteJsons.xsecToken(item);
            if (noteId.isBlank()) {
                continue;
            }
            try {
                JsonNode detail = xhsNativeClient.noteDetail(cookie.get(), noteId, xsecToken, XSEC_SOURCE_PC_SEARCH);
                String photoUrl = firstPhoto(detail);
                if (!photoUrl.isBlank()) {
                    log.info("[XHS] 图片搜索成功 keyword={} noteId={} elapsedMs={}",
                        keyword, noteId, System.currentTimeMillis() - startedAt);
                    return photoUrl;
                }
            } catch (XhsCookieExpiredException ex) {
                throw ex;
            } catch (Exception ex) {
                log.debug("[XHS] 图片候选详情失败 keyword={} noteId={} reason={}", keyword, noteId, ex.getMessage());
            }
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
    public ContentCityContext collectCity(String cookie, ContentCityRequest request) {
        String query = request.city() + " " + request.keyword() + " 旅游 景点攻略";
        long startedAt = System.currentTimeMillis();
        log.info("[XHS] 开始搜索城市游记 city={} keyword={} query={}", request.city(), request.keyword(), query);
        JsonNode search = xhsNativeClient.searchNotes(cookie, query, 1, 0, XhsApiDefaults.SEARCH_PAGE_SIZE);
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
                String noteId = XhsNoteJsons.noteId(item);
                String xsecToken = XhsNoteJsons.xsecToken(item);
                JsonNode noteCard = item.path("note_card");
                String title = XhsNoteJsons.title(noteCard);
                String desc = "";
                if (!noteId.isBlank()) {
                    try {
                        log.debug("[XHS] 获取笔记详情 city={} noteId={} title={}", request.city(), noteId, truncate(title, 40));
                        desc = noteDescription(xhsNativeClient.noteDetail(cookie, noteId, xsecToken, XSEC_SOURCE_PC_SEARCH));
                    } catch (XhsCookieExpiredException ex) {
                        throw ex;
                    } catch (Exception ex) {
                        log.warn("[XHS] 小红书详情获取失败 city={} noteId={} title={} reason={}",
                            request.city(), noteId, truncate(title, 50), ex.getMessage());
                    }
                }
                if (!desc.isBlank()) {
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
                TravelDataSource.XHS,
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
        return new ContentCityContext(request.city(), request.keyword(), TravelDataSource.XHS, rawText, candidates, message);
    }

    public List<ContentAttractionCandidate> extractAttractions(ContentCityRequest request, String rawText) {
        log.info("[XHS] 开始 LLM 提炼小红书景点 city={} rawLength={} aiAvailable={}",
            request.city(), rawText.length(), aiAgentService.isAvailable());
        Optional<List<ContentAttractionCandidate>> candidates = structuredOutputService.callForType(
            TripstarAgent.XHS_EXTRACTION,
            new org.springframework.core.ParameterizedTypeReference<List<ContentAttractionCandidate>>() {
            },
            promptResourceService.load(TripstarPrompt.XHS_EXTRACTION_SYSTEM),
            promptResourceService.render(TripstarPrompt.XHS_EXTRACTION_USER, Map.of(
                TripstarPromptVariable.CITY, request.city(),
                TripstarPromptVariable.KEYWORD, request.keyword(),
                TripstarPromptVariable.LANGUAGE, request.safeLanguage(),
                TripstarPromptVariable.RAW_TEXT, rawText,
                TripstarPromptVariable.FORMAT, structuredOutputService.format(new org.springframework.core.ParameterizedTypeReference<List<ContentAttractionCandidate>>() {
                })
            )),
            "xhs-extraction-" + request.city()
        );
        if (candidates.isEmpty()) {
            log.info("[XHS] LLM 未返回提炼结果 city={}", request.city());
            return List.of();
        }
        log.info("[XHS] LLM 提炼解析成功 city={} candidateCount={}", request.city(), candidates.get().size());
        return candidates.get();
    }

    public Optional<String> cookie() {
        return runtimeSettingsService.stringValue(TripstarSettingKeys.XHS_COOKIE)
            .map(XhsCookieUtils::normalize)
            .filter(value -> !value.isBlank());
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
