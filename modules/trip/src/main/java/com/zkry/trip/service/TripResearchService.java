package com.zkry.trip.service;

import com.zkry.ai.agent.TripstarAgent;
import com.zkry.ai.prompt.TripstarPrompt;
import com.zkry.ai.prompt.TripstarPromptVariable;
import com.zkry.ai.service.AiStructuredOutputService;
import com.zkry.ai.service.PromptResourceService;
import com.zkry.common.core.config.TripstarRuntimeSettingsService;
import com.zkry.common.core.config.TripstarSettingKeys;
import com.zkry.common.core.constant.TravelDataSource;
import com.zkry.common.core.exception.BizException;
import com.zkry.common.json.utils.JsonUtils;
import com.zkry.content.config.XhsMode;
import com.zkry.content.dto.ContentCityContext;
import com.zkry.content.dto.ContentCityRequest;
import com.zkry.content.dto.ContentPlanningContext;
import com.zkry.content.service.TravelContentService;
import com.zkry.content.service.XhsDetailTools;
import com.zkry.content.service.XhsSearchTools;
import com.zkry.map.dto.MapCityContext;
import com.zkry.map.dto.MapPlanningContext;
import com.zkry.map.dto.MapPoi;
import com.zkry.map.dto.MapPoint;
import com.zkry.map.dto.MapWeatherForecast;
import com.zkry.map.service.AmapGeoPoiTools;
import com.zkry.map.service.AmapHotelTools;
import com.zkry.map.service.AmapWeatherTools;
import com.zkry.trip.constant.TripTaskMessages;
import com.zkry.trip.constant.TravelResearchMessages;
import com.zkry.trip.dto.TravelResearchResult;
import com.zkry.trip.dto.TripRequest;
import com.zkry.trip.dto.XhsSearchResearchResult;
import com.zkry.trip.prompt.TripPlannerPrompts;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 旅行资料研究工作流。
 *
 * <p>这里是 Java 版 TripStar 学习 Spring AI Alibaba Agent 的核心入口。和旧版“一个
 * 研究 Agent 拿全部工具”不同，现在由 Java 控制阶段顺序，每个阶段只给 Agent
 * 当前允许使用的工具白名单。这样既能学习 ReactAgent 调工具，又能让前端进度对应真实执行。
 */
@Service
public class TripResearchService {

    private static final Logger log = LoggerFactory.getLogger(TripResearchService.class);

    private final TravelContentService travelContentService;
    private final XhsSearchTools xhsSearchTools;
    private final XhsDetailTools xhsDetailTools;
    private final AmapGeoPoiTools amapGeoPoiTools;
    private final AmapWeatherTools amapWeatherTools;
    private final AmapHotelTools amapHotelTools;
    private final AiStructuredOutputService structuredOutputService;
    private final PromptResourceService promptResourceService;
    private final TripstarRuntimeSettingsService runtimeSettingsService;

    @Value("${tripstar.content.xhs.mode:service}")
    private String xhsMode;

    public TripResearchService(
        TravelContentService travelContentService,
        XhsSearchTools xhsSearchTools,
        XhsDetailTools xhsDetailTools,
        AmapGeoPoiTools amapGeoPoiTools,
        AmapWeatherTools amapWeatherTools,
        AmapHotelTools amapHotelTools,
        AiStructuredOutputService structuredOutputService,
        PromptResourceService promptResourceService,
        TripstarRuntimeSettingsService runtimeSettingsService
    ) {
        this.travelContentService = travelContentService;
        this.xhsSearchTools = xhsSearchTools;
        this.xhsDetailTools = xhsDetailTools;
        this.amapGeoPoiTools = amapGeoPoiTools;
        this.amapWeatherTools = amapWeatherTools;
        this.amapHotelTools = amapHotelTools;
        this.structuredOutputService = structuredOutputService;
        this.promptResourceService = promptResourceService;
        this.runtimeSettingsService = runtimeSettingsService;
    }

    public ResearchContext research(String taskId, TripRequest request) {
        return research(taskId, request, TripResearchProgressReporter.noop());
    }

    /**
     * 资料研究阶段的总入口。
     *
     * <p>阶段顺序由 Java 保证：
     * 小红书搜索/详情 -> 高德 POI -> 高德天气 -> 高德酒店餐饮 -> 合并上下文。
     * 每个阶段内部仍由 ReactAgent 自主决定关键词和参数。
     */
    public ResearchContext research(
        String taskId,
        TripRequest request,
        TripResearchProgressReporter progressReporter
    ) {
        TripResearchProgressReporter reporter = progressReporter == null
            ? TripResearchProgressReporter.noop()
            : progressReporter;
        XhsMode mode = XhsMode.from(runtimeSettingsService.stringValue(TripstarSettingKeys.XHS_MODE).orElse(xhsMode));
        long startedAt = System.currentTimeMillis();
        log.info("[Research] 开始分阶段旅行资料研究 taskId={} xhsMode={} useService={} useTool={} cities={} preferences={} freeTextLength={}",
            taskId,
            mode.value(),
            mode.useService(),
            mode.useTool(),
            request.normalizedCities().stream().map(city -> city.city() + ":" + city.safeDays()).toList(),
            request.safePreferences(),
            request.free_text_input() == null ? 0 : request.free_text_input().length());


        ContentPlanningContext serviceContent = collectServiceContentIfNeeded(request, mode, reporter);
        if (mode.useService() && !serviceContent.realData()) {
            failXhsStage(taskId, TripTaskStage.XHS_SEARCH, TravelResearchMessages.xhsContentNoRealData(serviceContent));
        }

        XhsSearchResearchResult xhsSearchResult = null;
        TravelResearchResult xhsToolResult = null;
        ContentPlanningContext toolContent = null;

        if (mode.useTool()) {
            reporter.report(TripTaskStage.ATTRACTION_SEARCH, TripTaskProgress.XHS_SEARCH, TripTaskMessages.XHS_SEARCH);
            xhsSearchResult = collectXhsSearchByAgent(taskId, request)
                .orElseThrow(() -> new BizException("小红书搜索 Agent 未返回结构化结果，请检查工具调用日志和模型输出格式。"));
            log.info("[Research] 小红书搜索 Agent 完成 taskId={} cities={} searchNoteCount={} toolCalls={} summary={} cityDetails={}",
                taskId,
                xhsSearchResult.safeCities().size(),
                searchNoteCount(xhsSearchResult),
                xhsSearchResult.safeToolCalls(),
                xhsSearchResult.safeSummary(),
                searchCityDetails(xhsSearchResult));

            if (!hasSearchNotes(xhsSearchResult)) {
                String reason = TravelResearchMessages.xhsSearchNoNotes(searchFailureDetail(xhsSearchResult));
                failXhsStage(taskId, TripTaskStage.XHS_SEARCH, reason);
            }

            reporter.report(TripTaskStage.ATTRACTION_SEARCH, TripTaskProgress.XHS_DETAIL, TripTaskMessages.XHS_DETAIL);
            xhsToolResult = collectXhsDetailsByAgent(taskId, request, xhsSearchResult)
                .orElseThrow(() -> new BizException("小红书详情 Agent 未返回结构化结果，请检查 xhs_note_detail 工具日志和模型输出格式。"));
            toolContent = requireContentStage(taskId, TripTaskStage.XHS_DETAIL, "小红书详情", xhsToolResult);
            log.info("[Research] 小红书详情 Agent 完成 taskId={} realData={} cities={} toolCalls={} summary={}",
                taskId,
                toolContent.realData(),
                toolContent.safeCities().size(),
                xhsToolResult.safeToolCalls(),
                xhsToolResult.safeSummary());
            if (!toolContent.realData()) {
                String reason = TravelResearchMessages.xhsContentNoRealData(toolContent);
                failXhsStage(taskId, TripTaskStage.XHS_DETAIL, reason);
            }
        }
        ensureXhsReadyBeforeMap(taskId, mode, serviceContent, toolContent);
        ContentPlanningContext xhsContentForPoi = contentForPoi(mode, serviceContent, toolContent);
        log.info("[Research] 准备让高德 POI Agent 校准小红书景点 taskId={} xhsCandidateCount={}",
            taskId, xhsCandidateCount(xhsContentForPoi));

        reporter.report(TripTaskStage.ATTRACTION_SEARCH, TripTaskProgress.AMAP_POI_SEARCH, TripTaskMessages.AMAP_POI_SEARCH);
        TravelResearchResult poiResult = collectMapByAgent(
            taskId,
            request,
            TripstarAgent.AMAP_POI_RESEARCH,
            TripstarPrompt.RESEARCH_AMAP_POI_SYSTEM,
            TripstarPrompt.RESEARCH_AMAP_POI_USER,
            "amap-poi",
            Map.of(TripstarPromptVariable.XHS_ATTRACTIONS, TripPlannerPrompts.xhsAttractionCandidatesBlock(xhsContentForPoi)),
            amapGeoPoiTools
        ).orElseThrow(() -> new BizException("高德 POI Agent 未返回结构化结果，请检查 amap_poi_search 工具调用日志。"));
        MapPlanningContext poiContext = ensureMapStageData(taskId, TripTaskStage.AMAP_POI_SEARCH, "高德 POI", poiResult);

        reporter.report(TripTaskStage.WEATHER_SEARCH, TripTaskProgress.WEATHER_SEARCH, TripTaskMessages.WEATHER_SEARCH);
        TravelResearchResult weatherResult = collectMapByAgent(
            taskId,
            request,
            TripstarAgent.AMAP_WEATHER_RESEARCH,
            TripstarPrompt.RESEARCH_AMAP_WEATHER_SYSTEM,
            TripstarPrompt.RESEARCH_AMAP_WEATHER_USER,
            "amap-weather",
            amapWeatherTools
        ).orElseThrow(() -> new BizException("高德天气 Agent 未返回结构化结果，请检查 amap_weather 工具调用日志。"));
        MapPlanningContext weatherContext = ensureMapStageData(taskId, TripTaskStage.WEATHER_SEARCH, "高德天气", weatherResult);

        reporter.report(TripTaskStage.HOTEL_SEARCH, TripTaskProgress.HOTEL_SEARCH, TripTaskMessages.HOTEL_SEARCH);
        TravelResearchResult hotelResult = collectMapByAgent(
            taskId,
            request,
            TripstarAgent.AMAP_HOTEL_RESEARCH,
            TripstarPrompt.RESEARCH_AMAP_HOTEL_SYSTEM,
            TripstarPrompt.RESEARCH_AMAP_HOTEL_USER,
            "amap-hotel",
            amapHotelTools
        ).orElseThrow(() -> new BizException("高德酒店 Agent 未返回结构化结果，请检查 amap_hotel_search / amap_restaurant_search 工具调用日志。"));
        MapPlanningContext hotelContext = ensureMapStageData(taskId, TripTaskStage.HOTEL_SEARCH, "高德酒店餐饮", hotelResult);

        reporter.report(TripTaskStage.PLANNING, TripTaskProgress.RESEARCH_MERGE, TripTaskMessages.RESEARCH_MERGE);
        ContentPlanningContext mergedContent = mergeContent(
            serviceContent,
            toolContent,
            mode
        );
        MapPlanningContext mergedMap = mergeMapContexts(request, poiContext, weatherContext, hotelContext);
        List<TravelResearchResult> stageResults = new ArrayList<>();
        if (xhsToolResult != null) {
            stageResults.add(xhsToolResult);
        }
        stageResults.add(poiResult);
        stageResults.add(weatherResult);
        stageResults.add(hotelResult);
        TravelResearchResult mergedResult = mergeResearchResult(
            mergedMap,
            mergedContent,
            xhsSearchResult,
            stageResults
        );

        log.info("[Research] 分阶段旅行资料研究完成 taskId={} xhsMode={} mapRealData={} mapCities={} contentRealData={} contentCities={} toolCalls={} elapsedMs={}",
            taskId,
            mode.value(),
            mergedMap.realData(),
            mergedMap.safeCities().size(),
            mergedContent.realData(),
            mergedContent.safeCities().size(),
            mergedResult.safeToolCalls(),
            System.currentTimeMillis() - startedAt);
        log.info("[Research] 合并研究摘要 taskId={} excludedPlaces={} constraints={} summary={}",
            taskId,
            safeList(mergedResult.excluded_places()),
            safeList(mergedResult.user_constraints()),
            mergedResult.safeSummary());
        return new ResearchContext(mergedMap, mergedContent, mergedResult);
    }

    private ContentPlanningContext collectServiceContentIfNeeded(
        TripRequest request,
        XhsMode mode,
        TripResearchProgressReporter reporter
    ) {
        if (!mode.useService()) {
            log.info("[Research] 小红书 service 模式未启用，跳过 service 采集");
            return null;
        }
        reporter.report(TripTaskStage.ATTRACTION_SEARCH, TripTaskProgress.XHS_SERVICE_SEARCH, "正在通过小红书 service 搜索真实游记...");
        return collectContentByService(request);
    }

    /**
     * 小红书 service 路径。
     *
     * <p>这条链路和 Python 原项目最接近：Java 按固定流程搜索笔记、拉详情，再调用
     * XhsExtractionAgent 提炼候选景点。它适合和 tool/both 模式做效果对比。
     */
    private ContentPlanningContext collectContentByService(TripRequest request) {
        List<ContentCityRequest> cityRequests = request.normalizedCities().stream()
            .map(city -> new ContentCityRequest(
                city.city(),
                city.safeDays(),
                request.safePreferences(),
                request.safeLanguage()
            ))
            .toList();
        log.info("[Research] 使用小红书 service 采集内容 cities={}", cityRequests.stream().map(ContentCityRequest::city).toList());
        long startedAt = System.currentTimeMillis();
        ContentPlanningContext context = travelContentService.collect(cityRequests);
        log.info("[Research] 小红书 service 采集完成 realData={} cities={} message={} elapsedMs={}",
            context.realData(), context.safeCities().size(), context.message(), System.currentTimeMillis() - startedAt);
        if (!context.realData()) {
            throw new BizException("小红书 service 未采集到真实游记内容：" + context.message());
        }
        return context;
    }

    private Optional<XhsSearchResearchResult> collectXhsSearchByAgent(String taskId, TripRequest request) {
        Map<String, String> variables = new LinkedHashMap<>(TripPlannerPrompts.requestVariables(request));
        variables.put(TripstarPromptVariable.FORMAT, structuredOutputService.format(XhsSearchResearchResult.class));
        String userPrompt = promptResourceService.render(TripstarPrompt.RESEARCH_XHS_SEARCH_USER, variables);
        log.info("[Research] 调用 XhsSearchAgent taskId={} agent={} tools={}",
            taskId, TripstarAgent.XHS_SEARCH.id(), xhsSearchTools.getClass().getSimpleName());
        return structuredOutputService.callForObject(
            TripstarAgent.XHS_SEARCH,
            XhsSearchResearchResult.class,
            promptResourceService.load(TripstarPrompt.RESEARCH_XHS_SEARCH_SYSTEM),
            userPrompt,
            taskId + "-xhs-search",
            xhsSearchTools
        );
    }

    private Optional<TravelResearchResult> collectXhsDetailsByAgent(
        String taskId,
        TripRequest request,
        XhsSearchResearchResult searchResult
    ) {
        Map<String, String> variables = new LinkedHashMap<>(TripPlannerPrompts.requestVariables(request));
        variables.put(TripstarPromptVariable.XHS_SEARCH_RESULTS, JsonUtils.toJsonString(searchResult));
        variables.put(TripstarPromptVariable.FORMAT, structuredOutputService.format(TravelResearchResult.class));
        String userPrompt = promptResourceService.render(TripstarPrompt.RESEARCH_XHS_DETAIL_USER, variables);
        log.info("[Research] 调用 XhsDetailAgent taskId={} agent={} searchCities={} tools={}",
            taskId,
            TripstarAgent.XHS_DETAIL.id(),
            searchResult.safeCities().size(),
            xhsDetailTools.getClass().getSimpleName());
        return structuredOutputService.callForObject(
            TripstarAgent.XHS_DETAIL,
            TravelResearchResult.class,
            promptResourceService.load(TripstarPrompt.RESEARCH_XHS_DETAIL_SYSTEM),
            userPrompt,
            taskId + "-xhs-detail",
            xhsDetailTools
        );
    }

    /**
     * 小红书是本项目的必需内容源。
     *
     * <p>工具方法会把单次失败返回给 Agent，让 Agent 有机会换关键词或换笔记继续尝试；
     * 但阶段结束后必须由 Java 工作流做硬校验。否则前端会看到高德、天气、酒店继续执行，
     * 最后才因为没有小红书内容失败，定位成本很高。
     */
    private void ensureXhsReadyBeforeMap(
        String taskId,
        XhsMode mode,
        ContentPlanningContext serviceContent,
        ContentPlanningContext toolContent
    ) {
        boolean serviceReady = mode.useService() && serviceContent != null && serviceContent.realData();
        boolean toolReady = mode.useTool() && toolContent != null && toolContent.realData();
        boolean ready = switch (mode) {
            case SERVICE -> serviceReady;
            case TOOL -> toolReady;
            case BOTH -> serviceReady && toolReady;
        };
        if (ready) {
            log.info("[Research] 小红书必需数据校验通过 taskId={} mode={} serviceReady={} toolReady={}",
                taskId, mode.value(), serviceReady, toolReady);
            return;
        }

        String reason = mode == XhsMode.BOTH
            ? TravelResearchMessages.xhsBothNoRealData(serviceContent, toolContent)
            : TravelResearchMessages.xhsContentNoRealData(mode.useService()
                ? serviceContent
                : toolContent);
        failXhsStage(taskId, TripTaskStage.XHS_DETAIL, reason);
    }

    private boolean hasSearchNotes(XhsSearchResearchResult result) {
        return searchNoteCount(result) > 0;
    }

    private int searchNoteCount(XhsSearchResearchResult result) {
        if (result == null) {
            return 0;
        }
        return result.safeCities().stream()
            .mapToInt(city -> (int) city.safeNotes().stream()
                .filter(note -> note != null && note.note_id() != null && !note.note_id().isBlank())
                .count())
            .sum();
    }

    private String searchCityDetails(XhsSearchResearchResult result) {
        return result.safeCities().stream()
            .map(city -> city.city() + "(keyword=" + safe(city.keyword()) + ", notes=" + city.safeNotes().size()
                + ", message=" + safe(city.message()) + ")")
            .toList()
            .toString();
    }

    private String searchFailureDetail(XhsSearchResearchResult result) {
        return "agentSummary=" + result.safeSummary()
            + "；agentCities=" + searchCityDetails(result)
            + "；请查看同 taskId 附近的 [XHS-Tool] searchNotes 日志确认工具是否真实调用、noteCount 是否为 0、是否有接口错误。";
    }

    private void failXhsStage(String taskId, String stage, String reason) {
        log.warn("[Research] 小红书阶段失败，终止后续研究 taskId={} stage={} reason={}",
            taskId, stage, reason);
        throw new BizException("小红书内容采集失败：" + reason);
    }

    private Optional<TravelResearchResult> collectMapByAgent(
        String taskId,
        TripRequest request,
        TripstarAgent agent,
        String systemPromptPath,
        String userPromptPath,
        String threadSuffix,
        Object tools
    ) {
        return collectMapByAgent(
            taskId,
            request,
            agent,
            systemPromptPath,
            userPromptPath,
            threadSuffix,
            Map.of(),
            tools
        );
    }

    private Optional<TravelResearchResult> collectMapByAgent(
        String taskId,
        TripRequest request,
        TripstarAgent agent,
        String systemPromptPath,
        String userPromptPath,
        String threadSuffix,
        Map<String, String> extraVariables,
        Object tools
    ) {
        Map<String, String> variables = new LinkedHashMap<>(TripPlannerPrompts.requestVariables(request));
        if (extraVariables != null && !extraVariables.isEmpty()) {
            variables.putAll(extraVariables);
        }
        variables.put(TripstarPromptVariable.FORMAT, structuredOutputService.format(TravelResearchResult.class));
        String userPrompt = promptResourceService.render(userPromptPath, variables);
        log.info("[Research] 调用地图阶段 Agent taskId={} agent={} tools={}",
            taskId, agent.id(), tools == null ? "[]" : tools.getClass().getSimpleName());
        Optional<TravelResearchResult> result = structuredOutputService.callForObject(
            agent,
            TravelResearchResult.class,
            promptResourceService.load(systemPromptPath),
            userPrompt,
            taskId + "-" + threadSuffix,
            tools
        );
        result.ifPresent(value -> log.info("[Research] 地图阶段 Agent 完成 taskId={} agent={} mapRealData={} mapCities={} toolCalls={} summary={}",
            taskId,
            agent.id(),
            value.map_context() != null && value.map_context().realData(),
            value.map_context() == null ? 0 : value.map_context().safeCities().size(),
            value.safeToolCalls(),
            value.safeSummary()));
        return result;
    }

    private ContentPlanningContext contentForPoi(
        XhsMode mode,
        ContentPlanningContext serviceContent,
        ContentPlanningContext toolContent
    ) {
        if (mode == XhsMode.SERVICE) {
            return serviceContent;
        }
        if (mode == XhsMode.TOOL) {
            return toolContent;
        }
        List<ContentCityContext> cities = new ArrayList<>();
        if (serviceContent != null) {
            cities.addAll(serviceContent.safeCities());
        }
        if (toolContent != null) {
            cities.addAll(toolContent.safeCities());
        }
        return new ContentPlanningContext(
            cities,
            serviceContent != null && serviceContent.realData() && toolContent != null && toolContent.realData(),
            TravelDataSource.XHS_BOTH,
            TravelResearchMessages.bothMessage(serviceContent, toolContent)
        );
    }

    private int xhsCandidateCount(ContentPlanningContext context) {
        if (context == null) {
            return 0;
        }
        return context.safeCities().stream()
            .mapToInt(city -> city.safeAttractions().size())
            .sum();
    }

    private ContentPlanningContext requireContentStage(
        String taskId,
        String stage,
        String label,
        TravelResearchResult result
    ) {
        ContentPlanningContext contentContext = result == null ? null : result.content_context();
        if (contentContext == null) {
            log.warn("[Research] {} 阶段失败，Agent 结构化输出缺少 content_context taskId={} stage={} summary={} toolCalls={}",
                label,
                taskId,
                stage,
                result == null ? "" : result.safeSummary(),
                result == null ? List.of() : result.safeToolCalls());
            throw new BizException(label + "采集失败：Agent 结构化输出缺少 content_context。");
        }
        return contentContext;
    }

    private MapPlanningContext ensureMapStageData(String taskId, String stage, String label, TravelResearchResult result) {
        MapPlanningContext mapContext = result == null ? null : result.map_context();
        if (mapContext == null) {
            log.warn("[Research] {} 阶段失败，Agent 结构化输出缺少 map_context taskId={} stage={} summary={} toolCalls={}",
                label,
                taskId,
                stage,
                result == null ? "" : result.safeSummary(),
                result == null ? List.of() : result.safeToolCalls());
            throw new BizException(label + "采集失败：Agent 结构化输出缺少 map_context。");
        }
        log.info("[Research] {} 阶段数据校验 taskId={} realData={} cities={} message={}",
            label, taskId, mapContext.realData(), mapContext.safeCities().size(), mapContext.message());
        if (!mapContext.realData()) {
            log.warn("[Research] {} 阶段失败，终止后续研究 taskId={} stage={} summary={} message={}",
                label, taskId, stage, result.safeSummary(), mapContext.message());
            throw new BizException(label + "采集失败：" + safe(mapContext.message()));
        }
        return mapContext;
    }

    /**
     * 合并小红书上下文。
     *
     * <p>service/tool 模式直接取对应结果；both 模式把两边上下文拼在一起，让 PlannerAgent
     * 有更多真实游记材料可参考。
     */
    private ContentPlanningContext mergeContent(
        ContentPlanningContext serviceContent,
        ContentPlanningContext toolContent,
        XhsMode mode
    ) {
        if (mode == XhsMode.SERVICE) {
            requireContentContext(serviceContent, "小红书 service");
            log.info("[Research] 小红书上下文采用 service 模式 realData={} cities={}",
                serviceContent.realData(), serviceContent.safeCities().size());
            return serviceContent;
        }
        if (mode == XhsMode.TOOL) {
            requireContentContext(toolContent, "小红书 tool");
            log.info("[Research] 小红书上下文采用 tool 模式 realData={} cities={}",
                toolContent.realData(), toolContent.safeCities().size());
            return toolContent;
        }

        requireContentContext(serviceContent, "小红书 service");
        requireContentContext(toolContent, "小红书 tool");
        List<ContentCityContext> cities = new ArrayList<>();
        cities.addAll(serviceContent.safeCities());
        cities.addAll(toolContent.safeCities());
        boolean realData = serviceContent.realData() && toolContent.realData();
        String message = TravelResearchMessages.bothMessage(serviceContent, toolContent);
        log.info("[Research] 小红书上下文采用 both 合并 serviceCities={} toolCities={} mergedCities={} realData={}",
            serviceContent.safeCities().size(), toolContent.safeCities().size(), cities.size(), realData);
        return new ContentPlanningContext(cities, realData, TravelDataSource.XHS_BOTH, message);
    }

    private void requireContentContext(ContentPlanningContext context, String label) {
        if (context == null) {
            throw new BizException(label + "上下文为空，流程配置与执行结果不一致。");
        }
    }

    private MapPlanningContext mergeMapContexts(
        TripRequest request,
        MapPlanningContext poiContext,
        MapPlanningContext weatherContext,
        MapPlanningContext hotelContext
    ) {
        Map<String, MapCityAccumulator> cities = new LinkedHashMap<>();
        request.normalizedCities().forEach(city -> cities.put(city.city(), new MapCityAccumulator(city.city())));
        mergeMapStage(cities, poiContext);
        mergeMapStage(cities, weatherContext);
        mergeMapStage(cities, hotelContext);

        List<MapCityContext> contexts = cities.values().stream()
            .map(MapCityAccumulator::toContext)
            .filter(MapCityContext::hasAnyData)
            .toList();
        boolean realData = !contexts.isEmpty();
        String message = "高德分阶段 Agent 采集完成：POI="
            + safe(poiContext.message())
            + "；天气="
            + safe(weatherContext.message())
            + "；酒店餐饮="
            + safe(hotelContext.message());
        log.info("[Research] 高德上下文合并完成 realData={} cities={} poiCities={} weatherCities={} hotelCities={}",
            realData,
            contexts.size(),
            poiContext.safeCities().size(),
            weatherContext.safeCities().size(),
            hotelContext.safeCities().size());
        return new MapPlanningContext(contexts, realData, TravelDataSource.AMAP, message);
    }

    private void mergeMapStage(Map<String, MapCityAccumulator> target, MapPlanningContext context) {
        for (MapCityContext cityContext : context.safeCities()) {
            if (cityContext == null || cityContext.city() == null || cityContext.city().isBlank()) {
                continue;
            }
            MapCityAccumulator accumulator = target.computeIfAbsent(cityContext.city(), MapCityAccumulator::new);
            accumulator.merge(cityContext);
        }
    }

    private TravelResearchResult mergeResearchResult(
        MapPlanningContext mapContext,
        ContentPlanningContext contentContext,
        XhsSearchResearchResult xhsSearchResult,
        List<TravelResearchResult> stageResults
    ) {
        LinkedHashSet<String> constraints = new LinkedHashSet<>(
            xhsSearchResult == null ? List.of() : xhsSearchResult.safeUserConstraints()
        );
        LinkedHashSet<String> excludedPlaces = new LinkedHashSet<>(
            xhsSearchResult == null ? List.of() : xhsSearchResult.safeExcludedPlaces()
        );
        LinkedHashSet<String> toolCalls = new LinkedHashSet<>(
            xhsSearchResult == null ? List.of() : xhsSearchResult.safeToolCalls()
        );
        List<String> summaries = new ArrayList<>();
        if (xhsSearchResult != null) {
            summaries.add(xhsSearchResult.safeSummary());
        }

        for (TravelResearchResult result : stageResults) {
            constraints.addAll(safeList(result.user_constraints()));
            excludedPlaces.addAll(safeList(result.excluded_places()));
            toolCalls.addAll(result.safeToolCalls());
            summaries.add(result.safeSummary());
        }
        return new TravelResearchResult(
            mapContext,
            contentContext,
            List.copyOf(constraints),
            List.copyOf(excludedPlaces),
            List.copyOf(toolCalls),
            String.join("；", summaries)
        );
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "无详细信息" : value;
    }

    public record ResearchContext(
        MapPlanningContext mapContext,
        ContentPlanningContext contentContext,
        TravelResearchResult researchResult
    ) {
    }

    private static final class MapCityAccumulator {

        private final String city;
        private MapPoint center;
        private final List<MapPoi> attractions = new ArrayList<>();
        private final List<MapPoi> hotels = new ArrayList<>();
        private final List<MapPoi> restaurants = new ArrayList<>();
        private final List<MapWeatherForecast> weatherForecasts = new ArrayList<>();

        private MapCityAccumulator(String city) {
            this.city = city;
        }

        private void merge(MapCityContext context) {
            if (center == null && context.center() != null && context.center().available()) {
                center = context.center();
            }
            attractions.addAll(context.safeAttractions());
            hotels.addAll(context.safeHotels());
            restaurants.addAll(context.safeRestaurants());
            weatherForecasts.addAll(context.safeWeatherForecasts());
        }

        private MapCityContext toContext() {
            return new MapCityContext(
                city,
                center,
                List.copyOf(attractions),
                List.copyOf(hotels),
                List.copyOf(restaurants),
                List.copyOf(weatherForecasts)
            );
        }
    }
}
