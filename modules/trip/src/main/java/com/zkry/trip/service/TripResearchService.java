package com.zkry.trip.service;

import com.zkry.ai.agent.TripstarAgent;
import com.zkry.ai.prompt.TripstarPrompt;
import com.zkry.ai.prompt.TripstarPromptVariable;
import com.zkry.ai.service.AiStructuredOutputService;
import com.zkry.ai.service.PromptResourceService;
import com.zkry.common.core.config.TripstarRuntimeSettingsService;
import com.zkry.common.core.config.TripstarSettingKeys;
import com.zkry.common.core.constant.TravelDataSource;
import com.zkry.content.config.XhsMode;
import com.zkry.content.dto.ContentCityContext;
import com.zkry.content.dto.ContentCityRequest;
import com.zkry.content.dto.ContentPlanningContext;
import com.zkry.content.service.TravelContentService;
import com.zkry.content.service.XhsTravelTools;
import com.zkry.map.dto.MapPlanningContext;
import com.zkry.map.service.AmapTravelTools;
import com.zkry.trip.constant.TravelResearchMessages;
import com.zkry.trip.dto.TravelResearchResult;
import com.zkry.trip.dto.TripRequest;
import com.zkry.trip.prompt.TripPlannerPrompts;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class TripResearchService {

    private static final Logger log = LoggerFactory.getLogger(TripResearchService.class);

    private final TravelContentService travelContentService;
    private final AmapTravelTools amapTravelTools;
    private final XhsTravelTools xhsTravelTools;
    private final AiStructuredOutputService structuredOutputService;
    private final PromptResourceService promptResourceService;
    private final TripstarRuntimeSettingsService runtimeSettingsService;

    @Value("${tripstar.content.xhs.mode:service}")
    private String xhsMode;

    public TripResearchService(
        TravelContentService travelContentService,
        AmapTravelTools amapTravelTools,
        XhsTravelTools xhsTravelTools,
        AiStructuredOutputService structuredOutputService,
        PromptResourceService promptResourceService,
        TripstarRuntimeSettingsService runtimeSettingsService
    ) {
        this.travelContentService = travelContentService;
        this.amapTravelTools = amapTravelTools;
        this.xhsTravelTools = xhsTravelTools;
        this.structuredOutputService = structuredOutputService;
        this.promptResourceService = promptResourceService;
        this.runtimeSettingsService = runtimeSettingsService;
    }

    /**
     * 资料研究阶段的总入口。
     *
     * <p>这里刻意没有直接写“查高德、查小红书”的细节，而是拆成两条路径：
     * 小红书 service 路径用于对标 Python 版的确定性采集；ReactAgent Tool 路径用于学习
     * Agent 如何根据用户需求主动调用高德/小红书工具。最终两条路径都会被整理成
     * PlannerAgent 能理解的上下文对象。
     */
    public ResearchContext research(String taskId, TripRequest request) {
        XhsMode mode = XhsMode.from(runtimeSettingsService.stringValue(TripstarSettingKeys.XHS_MODE).orElse(xhsMode));
        long startedAt = System.currentTimeMillis();
        log.info("[Research] 开始旅行资料研究 taskId={} xhsMode={} useService={} useTool={} cities={} preferences={} freeTextLength={}",
            taskId,
            mode.value(),
            mode.useService(),
            mode.useTool(),
            request.normalizedCities().stream().map(city -> city.city() + ":" + city.safeDays()).toList(),
            request.safePreferences(),
            request.free_text_input() == null ? 0 : request.free_text_input().length());

        ContentPlanningContext serviceContent = mode.useService()
            ? collectContentByService(request)
            : TravelResearchMessages.xhsServiceDisabled();

        TravelResearchResult agentResult = collectByAgent(taskId, request, mode)
            .orElseGet(TravelResearchMessages::agentResultMissing);

        ContentPlanningContext mergedContent = mergeContent(serviceContent, agentResult.safeContentContext(), mode);
        MapPlanningContext mapContext = agentResult.safeMapContext();

        log.info("[Research] 旅行资料研究完成 taskId={} xhsMode={} mapRealData={} mapCities={} contentRealData={} contentCities={} toolCalls={}",
            taskId,
            mode.value(),
            mapContext.realData(),
            mapContext.safeCities().size(),
            mergedContent.realData(),
            mergedContent.safeCities().size(),
            agentResult.safeToolCalls());
        log.info("[Research] Agent 研究摘要 taskId={} excludedPlaces={} constraints={} summary={} elapsedMs={}",
            taskId,
            safeList(agentResult.excluded_places()),
            safeList(agentResult.user_constraints()),
            agentResult.safeSummary(),
            System.currentTimeMillis() - startedAt);
        return new ResearchContext(mapContext, mergedContent, agentResult);
    }

    /**
     * 通过 TravelResearchAgent 采集地图和可选小红书上下文。
     *
     * <p>高德工具始终交给 Agent 调用；小红书工具是否开放给 Agent，由 {@link XhsMode}
     * 决定。这样你可以比较 service、tool、both 三种模式下模型理解用户需求的差异。
     */
    private Optional<TravelResearchResult> collectByAgent(String taskId, TripRequest request, XhsMode mode) {
        Map<String, String> variables = new LinkedHashMap<>(TripPlannerPrompts.requestVariables(request));
        variables.put(TripstarPromptVariable.XHS_MODE, mode.value());
        variables.put(TripstarPromptVariable.FORMAT, structuredOutputService.format(TravelResearchResult.class));
        String userPrompt = promptResourceService.render(TripstarPrompt.RESEARCH_USER, variables);

        Object[] tools = mode.useTool()
            ? new Object[] {amapTravelTools, xhsTravelTools}
            : new Object[] {amapTravelTools};
        log.info("[Research] 调用 TravelResearchAgent taskId={} agent={} xhsMode={} toolCount={} tools={}",
            taskId, TripstarAgent.TRAVEL_RESEARCH.id(), mode.value(), tools.length, toolNames(tools));
        return structuredOutputService.callForObject(
            TripstarAgent.TRAVEL_RESEARCH,
            TravelResearchResult.class,
            promptResourceService.load(TripstarPrompt.RESEARCH_SYSTEM),
            userPrompt,
            taskId + "-research",
            tools
        );
    }

    /**
     * 小红书 service 路径。
     *
     * <p>这条链路不让 Agent 决定怎么搜，而是 Java 按固定流程搜索笔记、拉详情、
     * 再用 XhsExtractionAgent 提炼候选景点。
     */
    private ContentPlanningContext collectContentByService(TripRequest request) {
        try {
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
            return context;
        } catch (Exception ex) {
            log.warn("[Research] 小红书 service 采集失败 reason={}", ex.getMessage());
            return TravelResearchMessages.xhsServiceFailed(ex.getMessage());
        }
    }

    /**
     * 合并小红书上下文。
     *
     * <p>service/tool 模式直接取对应结果；both 模式会把两边城市上下文拼在一起，
     * 保留更多材料给 PlannerAgent，由模型最终决定是否采用。
     */
    private ContentPlanningContext mergeContent(
        ContentPlanningContext serviceContent,
        ContentPlanningContext toolContent,
        XhsMode mode
    ) {
        if (mode == XhsMode.SERVICE) {
            log.info("[Research] 小红书上下文采用 service 模式 realData={} cities={}",
                serviceContent.realData(), serviceContent.safeCities().size());
            return serviceContent;
        }
        if (mode == XhsMode.TOOL) {
            log.info("[Research] 小红书上下文采用 tool 模式 realData={} cities={}",
                toolContent.realData(), toolContent.safeCities().size());
            return toolContent;
        }

        List<ContentCityContext> cities = new ArrayList<>();
        cities.addAll(serviceContent.safeCities());
        cities.addAll(toolContent.safeCities());
        boolean realData = serviceContent.realData() || toolContent.realData();
        String message = TravelResearchMessages.bothMessage(serviceContent, toolContent);
        log.info("[Research] 小红书上下文采用 both 合并 serviceCities={} toolCities={} mergedCities={} realData={}",
            serviceContent.safeCities().size(), toolContent.safeCities().size(), cities.size(), realData);
        return new ContentPlanningContext(cities, realData, TravelDataSource.XHS_BOTH, message);
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private String toolNames(Object[] tools) {
        if (tools == null || tools.length == 0) {
            return "[]";
        }
        return java.util.Arrays.stream(tools)
            .map(tool -> tool == null ? "null" : tool.getClass().getSimpleName())
            .toList()
            .toString();
    }

    public record ResearchContext(
        MapPlanningContext mapContext,
        ContentPlanningContext contentContext,
        TravelResearchResult researchResult
    ) {
    }
}
