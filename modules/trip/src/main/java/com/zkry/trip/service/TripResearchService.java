package com.zkry.trip.service;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.zkry.ai.agent.TripstarAgent;
import com.zkry.ai.prompt.TripstarPrompt;
import com.zkry.ai.prompt.TripstarPromptVariable;
import com.zkry.ai.service.AiStructuredOutputService;
import com.zkry.ai.service.PromptResourceService;
import com.zkry.common.core.config.TripstarRuntimeSettingsService;
import com.zkry.common.core.config.TripstarSettingKeys;
import com.zkry.common.core.constant.TravelDataSource;
import com.zkry.common.core.exception.BizException;
import com.zkry.common.core.exception.CommonErrorCode;
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
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 旅行资料研究工作流。
 *
 * <p>这是 Java 版 TripStar 最适合学习 Agent 编排的类。阅读时建议按这个顺序看：
 *
 * <p>1. {@link #research(String, TripRequest, TripResearchProgressReporter)}：资料研究入口；
 * 2. {@link #buildResearchGraph(ResearchGraphRun)}：Spring AI Alibaba Graph 节点和边；
 * 3. {@code graphXxx(...)} 系列方法：每个节点真正执行的业务；
 * 4. {@link ResearchGraphRun}：Graph 节点之间共享的中间结果。
 *
 * <p>这里没有让一个大 Agent 一次性拿全部工具，而是由 StateGraph 保证顺序：
 * 小红书 -> 高德 POI -> 天气 -> 酒店 -> 合并。每个节点内部仍然交给 ReactAgent
 * 自己决定关键词、参数和工具调用。
 *
 * <p>如果你只想先把主线跑通，可以按下面这条线读，不用来回跳：
 *
 * <pre>
 * research(...)
 *   -> buildResearchGraph(...)
 *   -> graphRouteXhsMode(...)             根据 xhs_mode 选择 service/tool/both 路线
 *   -> graphCollectServiceContent(...)    service 或 both 才执行
 *   -> graphSearchXhs(...)                tool 或 both 才执行，只拿搜索工具
 *   -> graphReadXhsDetails(...)           tool 或 both 才执行，只拿详情工具
 *   -> graphCheckXhsReady(...)            小红书必须成功，否则立刻停
 *   -> graphCollectAmapPoi(...)           高德 POI Agent
 *   -> graphCollectWeather(...)           高德天气 Agent
 *   -> graphCollectHotel(...)             高德酒店餐饮 Agent
 *   -> graphMergeResearchContext(...)     合并给后续规划 Agent 使用
 * </pre>
 *
 * <p>工具真正在哪里调用：本类不直接请求小红书或高德接口，而是把对应 Tools
 * 传给 structuredOutputService.callForObject(...)。ReactAgent 在那里决定何时调用工具，
 * 工具类再去请求真实接口。
 */
@Service
public class TripResearchService {

    private static final Logger log = LoggerFactory.getLogger(TripResearchService.class);

    /*
     * Graph 节点名称。
     *
     * 这些字符串只服务于 Spring AI Alibaba StateGraph，用来说明“工作流里有哪些节点”。
     * 它们不是推给前端的 stage。前端为了兼容原 Vue 项目，仍然只接收
     * attraction_search / weather_search / hotel_search / planning 等大阶段。
     */
    private static final String NODE_XHS_MODE_ROUTE = "xhs_mode_route";
    private static final String NODE_XHS_SERVICE = "xhs_service_optional";
    private static final String NODE_XHS_SEARCH = "xhs_search_agent";
    private static final String NODE_XHS_DETAIL = "xhs_detail_agent";
    private static final String NODE_XHS_READY = "xhs_ready_check";
    private static final String NODE_AMAP_POI = "amap_poi_agent";
    private static final String NODE_WEATHER = "amap_weather_agent";
    private static final String NODE_HOTEL = "amap_hotel_agent";
    private static final String NODE_MERGE = "merge_research_context";

    /*
     * 条件边分支名称。
     *
     * addConditionalEdges(...) 需要一个“分支名 -> 下一个节点”的映射。
     * 这些常量就是分支名，不是业务 stage，也不会返回给前端。
     */
    private static final String ROUTE_XHS_SERVICE = "xhs_service";
    private static final String ROUTE_XHS_TOOL = "xhs_tool";
    private static final String ROUTE_XHS_BOTH = "xhs_both";
    private static final String ROUTE_SERVICE_READY = "service_ready";
    private static final String ROUTE_SERVICE_THEN_TOOL = "service_then_tool";

    /*
     * Graph 每个节点需要返回一个 Map。这里放一个很轻的标记，主要用于调试：
     * 如果以后要打印 Graph 最后跑到哪个节点，可以从状态里看 last_node。
     * 真正的业务数据没有塞进 Graph state，而是保存在 ResearchGraphRun 里。
     */
    private static final String GRAPH_LAST_NODE = "last_node";

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
     * <p>这个方法做三件事：
     *
     * <p>1. 从运行时配置读取小红书模式：service / tool / both；
     * 2. 创建 {@link ResearchGraphRun}，它保存每个 Graph 节点的中间结果；
     * 3. 构建并执行 StateGraph，最后从 run.researchContext 拿到合并后的资料上下文。
     *
     * <p>注意：Graph 负责节点顺序，ReactAgent 负责节点内部的工具调用。
     * 也就是说，这里不会直接出现 xhs_search_notes、amap_poi_search 之类的调用；
     * 它们发生在 Agent 执行过程中，日志里会看到 [XHS-Tool] / [AMAP-Tool]。
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
        log.info("[ResearchGraph] 开始 Graph 资料研究 taskId={} xhsMode={} useService={} useTool={} cities={} preferences={} freeTextLength={}",
            taskId,
            mode.value(),
            mode.useService(),
            mode.useTool(),
            request.normalizedCities().stream().map(city -> city.city() + ":" + city.safeDays()).toList(),
            request.safePreferences(),
            request.free_text_input() == null ? 0 : request.free_text_input().length());

        /*
         * ResearchGraphRun 是本次资料研究的“工作台”。
         * 每个节点往里面写自己的结果，后面的节点再从里面读。
         * 这样比把所有对象都塞进 Graph 的 Map state 更直观，调试也简单。
         */
        ResearchGraphRun run = new ResearchGraphRun(taskId, request, reporter, mode);
        try {
            /*
             * 这里真正启动 Spring AI Alibaba Graph。
             * invoke(...) 返回的是 Graph 框架的最终 state；本项目最终需要的业务结果
             * 已经由 merge 节点写到 run.researchContext 里。
             */
            buildResearchGraph(run)
                .invoke(Map.of("taskId", taskId))
                .orElseThrow(() -> new BizException("资料研究 Graph 没有返回最终状态。"));

            ResearchContext context = run.researchContext;
            if (context == null) {
                throw new BizException("资料研究 Graph 执行结束，但没有生成 ResearchContext。");
            }
            log.info("[ResearchGraph] Graph 资料研究完成 taskId={} xhsMode={} mapRealData={} mapCities={} contentRealData={} contentCities={} toolCalls={} elapsedMs={}",
                taskId,
                mode.value(),
                context.mapContext().realData(),
                context.mapContext().safeCities().size(),
                context.contentContext().realData(),
                context.contentContext().safeCities().size(),
                context.researchResult().safeToolCalls(),
                System.currentTimeMillis() - startedAt);
            log.info("[ResearchGraph] 合并研究摘要 taskId={} excludedPlaces={} constraints={} summary={}",
                taskId,
                safeList(context.researchResult().excluded_places()),
                safeList(context.researchResult().user_constraints()),
                context.researchResult().safeSummary());
            return context;
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            Throwable root = unwrapGraphException(ex);
            if (root instanceof BizException bizException) {
                throw bizException;
            }
            throw new BizException(CommonErrorCode.BUSINESS_ERROR, "资料研究 Graph 执行失败：" + root.getMessage(), root);
        }
    }

    /**
     * 构建资料研究 StateGraph。
     *
     * <p>这是理解本类最关键的方法。读这里时先不要跳到每个节点内部，先看清楚边：
     *
     * <p>START -> 小红书模式路由。
     *
     * <p>路由后有三条正常路线：
     * service：service 采集 -> 小红书数据校验 -> 高德；
     * tool：搜索 Agent -> 详情 Agent -> 小红书数据校验 -> 高德；
     * both：service 采集 -> 搜索 Agent -> 详情 Agent -> 小红书数据校验 -> 高德。
     *
     * <p>这版用条件边表达小红书模式，不再让节点内部偷偷“跳过”。
     * 读 Graph 时就能看出当前配置会走哪条线。
     */
    private CompiledGraph buildResearchGraph(ResearchGraphRun run) {
        try {
            return new StateGraph()
                /*
                 * 1. 注册“小红书模式路由”节点。
                 *
                 * 这个节点本身不做采集，只记录当前 xhs_mode。
                 * 它的下一步由下面的第一条 addConditionalEdges(...) 决定：
                 * service / tool / both 会走不同路线。
                 */
                .addNode(NODE_XHS_MODE_ROUTE, AsyncNodeAction.node_async(state -> graphRouteXhsMode(run)))
                /*
                 * 2. 注册“小红书 service 采集”节点。
                 *
                 * 只有 service 和 both 模式会进入这里。
                 * 它走的是 Java service 固定流程：搜索小红书 -> 拉详情 -> LLM 提炼候选景点。
                 */
                .addNode(NODE_XHS_SERVICE, AsyncNodeAction.node_async(state -> graphCollectServiceContent(run)))
                /*
                 * 3. 注册“小红书搜索 Agent”节点。
                 *
                 * 只有 tool 和 both 模式会进入这里。
                 * 这个 Agent 只拿 XhsSearchTools，所以它只能调用 xhs_search_notes 搜索笔记。
                 */
                .addNode(NODE_XHS_SEARCH, AsyncNodeAction.node_async(state -> graphSearchXhs(run)))
                /*
                 * 4. 注册“小红书详情 Agent”节点。
                 *
                 * 搜索节点拿到 note_id / xsec_token 后，详情节点再调用 xhs_note_detail。
                 * 这里拆成两个 Agent，是为了让“搜索失败”和“详情失败”在日志里分开。
                 */
                .addNode(NODE_XHS_DETAIL, AsyncNodeAction.node_async(state -> graphReadXhsDetails(run)))
                /*
                 * 5. 注册“小红书 ready 校验”节点。
                 *
                 * 所有小红书路线最后都会汇入这里。
                 * 如果 service/tool/both 没有拿到真实小红书内容，这里会抛 BizException，
                 * 后面的高德 POI、天气、酒店就不会继续跑。
                 */
                .addNode(NODE_XHS_READY, AsyncNodeAction.node_async(state -> graphCheckXhsReady(run)))
                /*
                 * 6. 注册“高德 POI Agent”节点。
                 *
                 * 这个节点读取前面小红书阶段提炼出的景点候选，
                 * 再让 Agent 调用高德地理编码和 POI 工具，把自然语言景点变成地图数据。
                 */
                .addNode(NODE_AMAP_POI, AsyncNodeAction.node_async(state -> graphCollectAmapPoi(run)))
                /*
                 * 7. 注册“高德天气 Agent”节点。
                 *
                 * 这个节点只给天气工具。这样 Agent 不会在天气阶段误调用酒店或 POI 工具，
                 * 阶段职责更清楚，前端进度也更真实。
                 */
                .addNode(NODE_WEATHER, AsyncNodeAction.node_async(state -> graphCollectWeather(run)))
                /*
                 * 8. 注册“高德酒店餐饮 Agent”节点。
                 *
                 * 这个节点负责住宿和餐饮搜索，最后会和 POI、天气一起合并成 MapPlanningContext。
                 */
                .addNode(NODE_HOTEL, AsyncNodeAction.node_async(state -> graphCollectHotel(run)))
                /*
                 * 9. 注册“合并研究上下文”节点。
                 *
                 * 前面每个节点只负责自己阶段的数据。
                 * 到这里才把小红书内容、高德地图数据、Agent 摘要、用户约束和工具调用记录合并。
                 */
                .addNode(NODE_MERGE, AsyncNodeAction.node_async(state -> graphMergeResearchContext(run)))
                /*
                 * 10. Graph 起点。
                 *
                 * StateGraph.START 是框架内置起点。
                 * 任务开始后先进入 xhs_mode_route，而不是直接进入某个采集节点。
                 */
                .addEdge(StateGraph.START, NODE_XHS_MODE_ROUTE)
                /*
                 * 11. 第一条条件边：根据 xhs_mode 选择小红书路线。
                 *
                 * routeFromXhsMode(run) 会返回一个分支名：
                 * - ROUTE_XHS_SERVICE：service 模式，进入 NODE_XHS_SERVICE；
                 * - ROUTE_XHS_TOOL：tool 模式，跳过 service，进入 NODE_XHS_SEARCH；
                 * - ROUTE_XHS_BOTH：both 模式，先进入 NODE_XHS_SERVICE。
                 *
                 * Map.of(...) 是“分支名 -> 下一个节点”的映射表。
                 */
                .addConditionalEdges(
                    NODE_XHS_MODE_ROUTE,
                    AsyncEdgeAction.edge_async(state -> routeFromXhsMode(run)),
                    Map.of(
                        ROUTE_XHS_SERVICE, NODE_XHS_SERVICE,
                        ROUTE_XHS_TOOL, NODE_XHS_SEARCH,
                        ROUTE_XHS_BOTH, NODE_XHS_SERVICE
                    )
                )
                /*
                 * 12. 第二条条件边：service 节点执行完以后去哪。
                 *
                 * service 模式：
                 *   service 采集完成后直接进入 NODE_XHS_READY 校验。
                 *
                 * both 模式：
                 *   service 采集完成后继续进入 NODE_XHS_SEARCH，
                 *   让小红书 Agent tool 再跑一遍搜索和详情，最后两边结果一起合并。
                 */
                .addConditionalEdges(
                    NODE_XHS_SERVICE,
                    AsyncEdgeAction.edge_async(state -> routeAfterXhsService(run)),
                    Map.of(
                        ROUTE_SERVICE_READY, NODE_XHS_READY,
                        ROUTE_SERVICE_THEN_TOOL, NODE_XHS_SEARCH
                    )
                )
                /*
                 * 13. 小红书 tool 固定边：搜索 Agent -> 详情 Agent。
                 *
                 * 只有 tool 和 both 路线会经过这里。
                 * 搜索阶段必须先返回 note_id / xsec_token，详情阶段才能读取正文和图片。
                 */
                .addEdge(NODE_XHS_SEARCH, NODE_XHS_DETAIL)
                /*
                 * 14. 小红书 tool 固定边：详情 Agent -> ready 校验。
                 *
                 * 详情 Agent 返回 content_context 后，统一进入 NODE_XHS_READY。
                 * 这样 service/tool/both 三种模式都会经过同一个小红书硬校验。
                 */
                .addEdge(NODE_XHS_DETAIL, NODE_XHS_READY)
                /*
                 * 15. 小红书通过后进入高德 POI。
                 *
                 * 这里体现了当前项目的业务顺序：先拿小红书游记和候选景点，
                 * 再用高德校准景点、地址、经纬度等地图数据。
                 */
                .addEdge(NODE_XHS_READY, NODE_AMAP_POI)
                /*
                 * 16. 高德 POI -> 天气。
                 *
                 * POI 阶段先确定城市和景点上下文，天气阶段再按城市查询天气预报。
                 */
                .addEdge(NODE_AMAP_POI, NODE_WEATHER)
                /*
                 * 17. 天气 -> 酒店餐饮。
                 *
                 * 天气数据拿到后，再进入住宿和餐饮搜索。
                 * 这两个阶段分开，是为了日志和前端进度更容易对应。
                 */
                .addEdge(NODE_WEATHER, NODE_HOTEL)
                /*
                 * 18. 酒店餐饮 -> 合并。
                 *
                 * 到这里，高德 POI、天气、酒店餐饮三个阶段都已经完成，
                 * 可以把分散在 run.poiContext / run.weatherContext / run.hotelContext 的结果合并。
                 */
                .addEdge(NODE_HOTEL, NODE_MERGE)
                /*
                 * 19. Graph 终点。
                 *
                 * merge 节点会把最终 ResearchContext 写入 run.researchContext。
                 * 到 END 后，research(...) 从 run.researchContext 取出结果返回给 TripTaskService。
                 */
                .addEdge(NODE_MERGE, StateGraph.END)
                /*
                 * 20. 编译 Graph。
                 *
                 * compile() 会检查节点、边和条件边映射是否有效。
                 * 如果节点名写错、边连接不合法，会抛 GraphStateException。
                 */
                .compile();
        } catch (GraphStateException ex) {
            /*
             * Graph 构建失败属于后端配置或代码错误，不是用户输入错误。
             * 包成 BizException 后，外层任务流程可以用统一方式记录失败并推送给前端。
             */
            throw new BizException(CommonErrorCode.BUSINESS_ERROR, "资料研究 Graph 构建失败：" + ex.getMessage(), ex);
        }
    }

    /**
     * 节点 1：小红书模式路由。
     *
     * <p>这个节点不采集数据，只把当前 xhs_mode 打到日志里。
     * 真正决定下一个节点的是 {@link #routeFromXhsMode(ResearchGraphRun)} 条件边。
     */
    private Map<String, Object> graphRouteXhsMode(ResearchGraphRun run) {
        log.info("[ResearchGraph] 小红书模式路由 taskId={} mode={} useService={} useTool={}",
            run.taskId, run.mode.value(), run.mode.useService(), run.mode.useTool());
        return graphStep(NODE_XHS_MODE_ROUTE);
    }

    /**
     * 从“小红书模式路由节点”选择下一条边。
     *
     * <p>service 和 both 都先进入 service 节点；区别在于 service 节点结束后，
     * {@link #routeAfterXhsService(ResearchGraphRun)} 会继续判断是直接校验，还是再进入 tool 节点。
     */
    private String routeFromXhsMode(ResearchGraphRun run) {
        String route = switch (run.mode) {
            case SERVICE -> ROUTE_XHS_SERVICE;
            case TOOL -> ROUTE_XHS_TOOL;
            case BOTH -> ROUTE_XHS_BOTH;
        };
        log.info("[ResearchGraph] 小红书模式条件边 taskId={} mode={} route={}",
            run.taskId, run.mode.value(), route);
        return route;
    }

    /**
     * service 节点结束后的条件边。
     *
     * <p>service 模式：service 成功后直接去小红书 ready 校验；
     * both 模式：service 成功后继续走 Agent tool 搜索和详情。
     */
    private String routeAfterXhsService(ResearchGraphRun run) {
        String route = run.mode == XhsMode.BOTH ? ROUTE_SERVICE_THEN_TOOL : ROUTE_SERVICE_READY;
        log.info("[ResearchGraph] 小红书 service 后续条件边 taskId={} mode={} route={}",
            run.taskId, run.mode.value(), route);
        return route;
    }

    /**
     * 节点 2：小红书 service 采集。
     *
     * <p>如果 xhs_mode=service 或 both，这里会走 {@link TravelContentService#collect(List)}：
     * Java 先确定性搜索小红书、拉详情、提炼景点候选。
     *
     * <p>如果 xhs_mode=tool，Graph 条件边不会进入这个节点。
     */
    private Map<String, Object> graphCollectServiceContent(ResearchGraphRun run) {
        run.reporter.report(TripTaskStage.ATTRACTION_SEARCH, TripTaskProgress.XHS_SERVICE_SEARCH, "正在通过小红书 service 搜索真实游记...");
        run.serviceContent = collectContentByService(run.request);
        if (!run.serviceContent.realData()) {
            failXhsStage(run.taskId, TripTaskStage.XHS_SEARCH, TravelResearchMessages.xhsContentNoRealData(run.serviceContent));
        }
        return graphStep(NODE_XHS_SERVICE);
    }

    /**
     * 节点 3：小红书搜索 Agent。
     *
     * <p>只在 xhs_mode=tool 或 both 时进入。这个 Agent 只能拿到 {@link XhsSearchTools}，
     * 也就是只能调用 xhs_search_notes，不能提前读详情，也不能查高德。
     *
     * <p>节点产物写入 run.xhsSearchResult，后面的详情节点会读取这些 note_id 和 xsec_token。
     */
    private Map<String, Object> graphSearchXhs(ResearchGraphRun run) {
        run.reporter.report(TripTaskStage.ATTRACTION_SEARCH, TripTaskProgress.XHS_SEARCH, TripTaskMessages.XHS_SEARCH);
        run.xhsSearchResult = collectXhsSearchByAgent(run.taskId, run.request)
            .orElseThrow(() -> new BizException("小红书搜索 Agent 未返回结构化结果，请检查工具调用日志和模型输出格式。"));
        log.info("[ResearchGraph] 小红书搜索 Agent 完成 taskId={} cities={} searchNoteCount={} toolCalls={} summary={} cityDetails={}",
            run.taskId,
            run.xhsSearchResult.safeCities().size(),
            searchNoteCount(run.xhsSearchResult),
            run.xhsSearchResult.safeToolCalls(),
            run.xhsSearchResult.safeSummary(),
            searchCityDetails(run.xhsSearchResult));

        if (!hasSearchNotes(run.xhsSearchResult)) {
            String reason = TravelResearchMessages.xhsSearchNoNotes(searchFailureDetail(run.xhsSearchResult));
            failXhsStage(run.taskId, TripTaskStage.XHS_SEARCH, reason);
        }
        return graphStep(NODE_XHS_SEARCH);
    }

    /**
     * 节点 4：小红书详情 Agent。
     *
     * <p>搜索节点只拿到笔记引用，这里才允许 Agent 调用 xhs_note_detail 拉正文和图片。
     * 拉到正文后，Agent 会整理成 {@link ContentPlanningContext}，写入 run.toolContent。
     *
     * <p>如果详情阶段没有真实正文，后面的高德阶段不会继续执行。
     */
    private Map<String, Object> graphReadXhsDetails(ResearchGraphRun run) {
        run.reporter.report(TripTaskStage.ATTRACTION_SEARCH, TripTaskProgress.XHS_DETAIL, TripTaskMessages.XHS_DETAIL);
        run.xhsToolResult = collectXhsDetailsByAgent(run.taskId, run.request, run.xhsSearchResult)
            .orElseThrow(() -> new BizException("小红书详情 Agent 未返回结构化结果，请检查 xhs_note_detail 工具日志和模型输出格式。"));
        run.toolContent = requireContentStage(run.taskId, TripTaskStage.XHS_DETAIL, "小红书详情", run.xhsToolResult);
        log.info("[ResearchGraph] 小红书详情 Agent 完成 taskId={} realData={} cities={} toolCalls={} summary={}",
            run.taskId,
            run.toolContent.realData(),
            run.toolContent.safeCities().size(),
            run.xhsToolResult.safeToolCalls(),
            run.xhsToolResult.safeSummary());
        if (!run.toolContent.realData()) {
            String reason = TravelResearchMessages.xhsContentNoRealData(run.toolContent);
            failXhsStage(run.taskId, TripTaskStage.XHS_DETAIL, reason);
        }
        return graphStep(NODE_XHS_DETAIL);
    }

    /**
     * 节点 5：小红书必需数据校验。
     *
     * <p>无论 service/tool/both 哪种模式，到这里都必须已经拿到真实小红书内容。
     * 这个校验放在高德之前，是为了失败时立刻停住，不出现“小红书失败了但天气酒店还在跑”的假进度。
     *
     * <p>校验通过后，会准备一份给高德 POI Agent 使用的小红书景点候选块。
     */
    private Map<String, Object> graphCheckXhsReady(ResearchGraphRun run) {
        ensureXhsReadyBeforeMap(run.taskId, run.mode, run.serviceContent, run.toolContent);
        run.xhsContentForPoi = contentForPoi(run.mode, run.serviceContent, run.toolContent);
        log.info("[ResearchGraph] 准备让高德 POI Agent 校准小红书景点 taskId={} xhsCandidateCount={}",
            run.taskId, xhsCandidateCount(run.xhsContentForPoi));
        return graphStep(NODE_XHS_READY);
    }

    /**
     * 节点 6：高德 POI Agent。
     *
     * <p>这个 Agent 拿到两个输入：
     * 1. 用户原始需求；
     * 2. 小红书阶段提炼出来的景点候选。
     *
     * <p>它调用高德 geocode / poi 工具，把小红书里的自然语言景点变成可规划的地图数据：
     * 名称、地址、经纬度、评分等。
     */
    private Map<String, Object> graphCollectAmapPoi(ResearchGraphRun run) {
        run.reporter.report(TripTaskStage.ATTRACTION_SEARCH, TripTaskProgress.AMAP_POI_SEARCH, TripTaskMessages.AMAP_POI_SEARCH);
        run.poiResult = collectMapByAgent(
            run.taskId,
            run.request,
            TripstarAgent.AMAP_POI_RESEARCH,
            TripstarPrompt.RESEARCH_AMAP_POI_SYSTEM,
            TripstarPrompt.RESEARCH_AMAP_POI_USER,
            "amap-poi",
            Map.of(TripstarPromptVariable.XHS_ATTRACTIONS, TripPlannerPrompts.xhsAttractionCandidatesBlock(run.xhsContentForPoi)),
            amapGeoPoiTools
        ).orElseThrow(() -> new BizException("高德 POI Agent 未返回结构化结果，请检查 amap_poi_search 工具调用日志。"));
        run.poiContext = ensureMapStageData(run.taskId, TripTaskStage.AMAP_POI_SEARCH, "高德 POI", run.poiResult);
        return graphStep(NODE_AMAP_POI);
    }

    /**
     * 节点 7：高德天气 Agent。
     *
     * <p>天气和 POI 分开做，是为了让前端进度真实，也方便日志定位。
     * 这个节点只给 Agent 天气工具，不给酒店和 POI 工具。
     */
    private Map<String, Object> graphCollectWeather(ResearchGraphRun run) {
        run.reporter.report(TripTaskStage.WEATHER_SEARCH, TripTaskProgress.WEATHER_SEARCH, TripTaskMessages.WEATHER_SEARCH);
        run.weatherResult = collectMapByAgent(
            run.taskId,
            run.request,
            TripstarAgent.AMAP_WEATHER_RESEARCH,
            TripstarPrompt.RESEARCH_AMAP_WEATHER_SYSTEM,
            TripstarPrompt.RESEARCH_AMAP_WEATHER_USER,
            "amap-weather",
            amapWeatherTools
        ).orElseThrow(() -> new BizException("高德天气 Agent 未返回结构化结果，请检查 amap_weather 工具调用日志。"));
        run.weatherContext = ensureMapStageData(run.taskId, TripTaskStage.WEATHER_SEARCH, "高德天气", run.weatherResult);
        return graphStep(NODE_WEATHER);
    }

    /**
     * 节点 8：高德酒店和餐饮 Agent。
     *
     * <p>这个节点负责住和吃。它会根据用户的住宿偏好、交通方式、备注等，调用酒店和餐饮工具。
     * 结果写入 run.hotelContext，最后和 POI、天气一起合并。
     */
    private Map<String, Object> graphCollectHotel(ResearchGraphRun run) {
        run.reporter.report(TripTaskStage.HOTEL_SEARCH, TripTaskProgress.HOTEL_SEARCH, TripTaskMessages.HOTEL_SEARCH);
        run.hotelResult = collectMapByAgent(
            run.taskId,
            run.request,
            TripstarAgent.AMAP_HOTEL_RESEARCH,
            TripstarPrompt.RESEARCH_AMAP_HOTEL_SYSTEM,
            TripstarPrompt.RESEARCH_AMAP_HOTEL_USER,
            "amap-hotel",
            amapHotelTools
        ).orElseThrow(() -> new BizException("高德酒店 Agent 未返回结构化结果，请检查 amap_hotel_search / amap_restaurant_search 工具调用日志。"));
        run.hotelContext = ensureMapStageData(run.taskId, TripTaskStage.HOTEL_SEARCH, "高德酒店餐饮", run.hotelResult);
        return graphStep(NODE_HOTEL);
    }

    /**
     * 节点 9：合并资料研究上下文。
     *
     * <p>前面的节点分别产生：
     * - 小红书内容上下文：serviceContent / toolContent；
     * - 高德地图上下文：poiContext / weatherContext / hotelContext；
     * - 各阶段 Agent 的 summary、tool_calls、用户约束和排除景点。
     *
     * <p>这里把它们合并成 {@link ResearchContext}，后面的 PlannerAgent 只需要读这个总上下文。
     */
    private Map<String, Object> graphMergeResearchContext(ResearchGraphRun run) {
        run.reporter.report(TripTaskStage.PLANNING, TripTaskProgress.RESEARCH_MERGE, TripTaskMessages.RESEARCH_MERGE);
        ContentPlanningContext mergedContent = mergeContent(run.serviceContent, run.toolContent, run.mode);
        MapPlanningContext mergedMap = mergeMapContexts(run.request, run.poiContext, run.weatherContext, run.hotelContext);
        List<TravelResearchResult> stageResults = new ArrayList<>();
        if (run.xhsToolResult != null) {
            stageResults.add(run.xhsToolResult);
        }
        stageResults.add(run.poiResult);
        stageResults.add(run.weatherResult);
        stageResults.add(run.hotelResult);
        TravelResearchResult mergedResult = mergeResearchResult(
            mergedMap,
            mergedContent,
            run.xhsSearchResult,
            stageResults
        );
        run.researchContext = new ResearchContext(mergedMap, mergedContent, mergedResult);
        return graphStep(NODE_MERGE);
    }

    /**
     * 每个 Graph 节点都要返回 Map。
     *
     * <p>这里返回 last_node 只是给 Graph state 一个可读标记。业务对象不从这个 Map 里传，
     * 而是存在 ResearchGraphRun 中，避免一堆强转和字符串 key 让源码更难读。
     */
    private Map<String, Object> graphStep(String node) {
        return Map.of(GRAPH_LAST_NODE, node);
    }

    /**
     * Graph 节点内部异常常会被 CompletableFuture 包一层。
     *
     * <p>这里把包装拆掉，让前端和日志看到真正的业务错误，比如“小红书没有返回笔记”，
     * 而不是只看到 CompletionException。
     */
    private Throwable unwrapGraphException(Throwable ex) {
        Throwable current = ex;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
            && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
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
        /*
         * 这里把用户请求渲染进小红书搜索 Prompt。
         * FORMAT 是 Spring AI 结构化输出格式说明，告诉模型必须返回 XhsSearchResearchResult。
         *
         * 这个方法只负责“准备 Prompt + 指定工具 + 调用 Agent”。
         * 真正的搜索请求在 XhsSearchTools.xhs_search_notes 里面完成。
         */
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
        /*
         * 搜索结果会原样放进详情 Agent 的 Prompt。
         * 详情 Agent 根据 note_id / xsec_token 调 xhs_note_detail，最后输出 TravelResearchResult。
         *
         * 这里故意把“搜索”和“详情”拆成两个 Agent：
         * 搜索 Agent 负责找候选，详情 Agent 负责读正文。这样日志能看清楚到底是哪一步失败。
         */
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
        /*
         * 地图类 Agent 的公共入口。
         * POI、天气、酒店三个节点都走这里，只是 agent、prompt 和 tools 不同。
         */
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
        /*
         * FORMAT 让模型按 TravelResearchResult 返回。
         * 这就是之前我们说的 Structured Output：模型不是随便写一段文本，
         * 而是按 Java record 的字段输出 JSON，再由 Spring AI 帮我们转成对象。
         */
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
        /*
         * 高德 POI Agent 需要知道“小红书推荐了哪些景点”。
         *
         * service 模式：用 Java service 确定性采集出来的小红书内容；
         * tool 模式：用小红书 Agent Tool 采集出来的小红书内容；
         * both 模式：两边内容都给 POI Agent，让它有更多候选可校准。
         */
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
        /*
         * Agent 最终返回的是 TravelResearchResult。
         *
         * 小红书详情阶段必须把真实笔记正文、图片、提炼出的景点候选放到
         * content_context 里。这里不再做“自动补救”，因为缺 content_context
         * 说明模型输出格式、Prompt 或工具调用某一环有问题，应该让流程立刻失败，
         * 这样日志能准确指向当前阶段。
         */
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
        /*
         * 高德三个节点都复用 TravelResearchResult：
         *
         * - POI Agent 返回 map_context.attractions / center；
         * - Weather Agent 返回 map_context.weatherForecasts；
         * - Hotel Agent 返回 map_context.hotels / restaurants。
         *
         * 这里统一校验 map_context，是为了保证每个 Agent 节点的契约一致：
         * “你可以自主调用工具，但最终必须给 Java 一个结构化 MapPlanningContext”。
         */
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
            /*
             * realData=false 表示 Agent 虽然返回了 JSON，但里面没有可用真实数据。
             * 例如高德接口没配 key、工具调用失败、城市解析失败，都应该在这里停住。
             */
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
        /*
         * 到合并节点时，小红书阶段已经通过 ensureXhsReadyBeforeMap 校验。
         * 这里不再猜测缺失数据，只按当前模式选择最终给规划 Agent 的内容源。
         */
        if (mode == XhsMode.SERVICE) {
            /*
             * service：Java service 固定流程采集。
             * 优点是可控，日志和异常更直观；缺点是 Agent 对搜索策略参与较少。
             */
            requireContentContext(serviceContent, "小红书 service");
            log.info("[Research] 小红书上下文采用 service 模式 realData={} cities={}",
                serviceContent.realData(), serviceContent.safeCities().size());
            return serviceContent;
        }
        if (mode == XhsMode.TOOL) {
            /*
             * tool：ReactAgent 自己调用 xhs_search_notes / xhs_note_detail。
             * 这是当前项目最适合学习 Agent Tool 调用的一条链路。
             */
            requireContentContext(toolContent, "小红书 tool");
            log.info("[Research] 小红书上下文采用 tool 模式 realData={} cities={}",
                toolContent.realData(), toolContent.safeCities().size());
            return toolContent;
        }

        /*
         * both：两条小红书链路都必须成功，然后把城市上下文拼在一起。
         * 这个模式主要用于你测试“确定性 service”和“Agent 自主 tool”的效果差异。
         */
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
        /*
         * 走到这里还为空，通常不是用户输入问题，而是代码流程配置不一致。
         * 例如 xhs_mode=tool，但前面的 tool 节点没有把 run.toolContent 写进去。
         */
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
        /*
         * 三个高德节点返回的都是 MapPlanningContext，但每个节点只填自己负责的部分：
         *
         * poiContext：景点、经纬度、城市中心点；
         * weatherContext：天气；
         * hotelContext：酒店和餐饮。
         *
         * PlannerAgent 希望看到的是“每个城市一个完整上下文”，所以这里按城市名合并。
         */
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
        /*
         * 把某一个地图阶段的数据合并进城市桶。
         *
         * 这里不关心 context 来自 POI、天气还是酒店，因为 MapCityAccumulator.merge(...)
         * 会把非空字段全部收进去。这样三段高德 Agent 可以独立运行，最后再按城市拼回去。
         */
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
        /*
         * 这个 TravelResearchResult 是“资料研究总结果”，不是某个单独 Agent 的原始返回。
         *
         * 合并内容包括：
         * - map_context：高德 POI / 天气 / 酒店餐饮合并后的地图上下文；
         * - content_context：小红书 service/tool/both 合并后的游记上下文；
         * - user_constraints：Agent 从用户备注里识别出的限制，比如“不想太累”；
         * - excluded_places：Agent 识别出的排除景点，比如“不想看滇池”；
         * - tool_calls：本轮资料研究实际调用过哪些工具，方便日志和学习追踪；
         * - summary：各阶段 Agent 的一句话摘要拼接。
         */
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

    /**
     * 一次 Graph 执行的共享上下文。
     *
     * <p>Spring AI Alibaba Graph 的每个节点都会收到一个 Map state。理论上我们可以把所有业务
     * 对象都塞到那个 Map 里，但读起来会变成一堆字符串 key 和类型强转。为了让学习成本低一点，
     * 本项目把本次任务的中间结果放到这个普通 Java 对象里。
     *
     * <p>阅读方式很简单：看字段名就能知道它由哪个节点写入。
     */
    private static final class ResearchGraphRun {

        /** 任务 id：串起日志、Agent threadId、前端进度。 */
        private final String taskId;
        /** 用户请求：城市、天数、偏好、备注等原始输入。 */
        private final TripRequest request;
        /** 进度上报器：Graph 节点执行到哪一步，就由它推给前端兼容阶段。 */
        private final TripResearchProgressReporter reporter;
        /** 小红书运行模式：service / tool / both。 */
        private final XhsMode mode;

        /*
         * 小红书 service 节点产物。
         * 写入：graphCollectServiceContent
         * 读取：graphCheckXhsReady / contentForPoi / mergeContent
         */
        private ContentPlanningContext serviceContent;
        /*
         * 小红书搜索 Agent 产物，只包含搜索到的笔记引用。
         * 写入：graphSearchXhs
         * 读取：graphReadXhsDetails / mergeResearchResult
         */
        private XhsSearchResearchResult xhsSearchResult;
        /*
         * 小红书详情 Agent 原始结构化结果。
         * 写入：graphReadXhsDetails
         * 读取：graphMergeResearchContext，用来合并 tool_calls / summary / 用户约束。
         */
        private TravelResearchResult xhsToolResult;
        /*
         * 从 xhsToolResult.content_context 拿出来的小红书正文上下文。
         * 写入：graphReadXhsDetails
         * 读取：graphCheckXhsReady / contentForPoi / mergeContent
         */
        private ContentPlanningContext toolContent;
        /*
         * 给高德 POI Agent 使用的小红书景点候选。
         * service/tool 模式取对应结果，both 模式合并两边结果。
         */
        private ContentPlanningContext xhsContentForPoi;
        /*
         * 高德 POI Agent 原始结果和校验后的 map_context。
         * poiResult 保留 Agent 摘要和工具调用记录，poiContext 保留地图业务数据。
         */
        private TravelResearchResult poiResult;
        private MapPlanningContext poiContext;
        /*
         * 高德天气 Agent 原始结果和校验后的 map_context。
         */
        private TravelResearchResult weatherResult;
        private MapPlanningContext weatherContext;
        /*
         * 高德酒店餐饮 Agent 原始结果和校验后的 map_context。
         */
        private TravelResearchResult hotelResult;
        private MapPlanningContext hotelContext;
        /*
         * Graph 最终产物。
         * 写入：graphMergeResearchContext
         * 读取：research(...) 返回给 TripTaskService，后续进入规划阶段。
         */
        private ResearchContext researchContext;

        private ResearchGraphRun(
            String taskId,
            TripRequest request,
            TripResearchProgressReporter reporter,
            XhsMode mode
        ) {
            this.taskId = taskId;
            this.request = request;
            this.reporter = reporter;
            this.mode = mode;
        }
    }

    /**
     * 资料研究最终交给规划阶段的三个对象。
     *
     * <p>TripTaskService 调用 research(...) 后拿到它，再把里面的数据交给后续 PlannerAgent：
     * mapContext 负责真实地图数据，contentContext 负责小红书游记内容，researchResult 负责
     * 用户约束、排除景点、工具调用记录和研究摘要。
     */
    public record ResearchContext(
        MapPlanningContext mapContext,
        ContentPlanningContext contentContext,
        TravelResearchResult researchResult
    ) {
    }

    /**
     * 按城市合并高德阶段数据的临时桶。
     *
     * <p>为什么需要它：POI、天气、酒店三个 Agent 是分阶段调用的，它们都会返回
     * MapCityContext，但每个 MapCityContext 只包含自己阶段的数据。如果直接把三个列表拼接，
     * PlannerAgent 会看到同一个城市出现三次；用这个桶可以把“北京的景点、北京的天气、北京的酒店”
     * 合成一个城市对象。
     */
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
            /*
             * 城市中心点通常由 POI 阶段提供。后续天气/酒店阶段如果也带了 center，
             * 这里保留第一个可用值，避免后来的空值或不稳定结果覆盖它。
             */
            if (center == null && context.center() != null && context.center().available()) {
                center = context.center();
            }
            attractions.addAll(context.safeAttractions());
            hotels.addAll(context.safeHotels());
            restaurants.addAll(context.safeRestaurants());
            weatherForecasts.addAll(context.safeWeatherForecasts());
        }

        private MapCityContext toContext() {
            /*
             * 结束合并时转回不可变的 MapCityContext。
             * 后续规划阶段只读这些数据，不应该继续修改这里的列表。
             */
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
