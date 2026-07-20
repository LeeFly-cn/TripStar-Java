package com.zkry.trip.service;

import com.zkry.ai.agent.TripstarAgent;
import com.zkry.ai.prompt.TripstarPrompt;
import com.zkry.ai.prompt.TripstarPromptVariable;
import com.zkry.ai.service.AiStructuredOutputService;
import com.zkry.ai.service.PromptResourceService;
import com.zkry.common.core.constant.TravelDataSource;
import com.zkry.common.core.exception.BizException;
import com.zkry.map.dto.MapAgentResult;
import com.zkry.map.dto.MapCityContext;
import com.zkry.map.dto.MapPlanningContext;
import com.zkry.map.dto.MapPoi;
import com.zkry.map.dto.MapPoint;
import com.zkry.map.dto.MapWeatherForecast;
import com.zkry.map.service.AmapWeatherTools;
import com.zkry.map.service.XhsNoteHotelFoodTools;
import com.zkry.trip.constant.TripTaskMessages;
import com.zkry.trip.dto.TripRequest;
import com.zkry.trip.dto.xhsnote.XhsNoteDayRoute;
import com.zkry.trip.dto.xhsnote.XhsNoteUnderstandingResult;
import com.zkry.trip.prompt.TripPlannerPrompts;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 指定笔记模式的高德研究流程。
 *
 * <p>Java 明确控制 POI 补全、天气查询、酒店餐饮补充三个阶段的先后顺序，保证前端进度真实。
 * POI 属于确定性数据校验，直接由 Service 完成；天气及酒店餐饮仍保留 ReactAgent 的决策能力。
 */
@Service
public class XhsNoteMapResearchService {

    private static final Logger log = LoggerFactory.getLogger(XhsNoteMapResearchService.class);

    private final AiStructuredOutputService structuredOutputService;
    private final PromptResourceService promptResourceService;
    private final XhsNotePoiEnrichmentService poiEnrichmentService;
    private final AmapWeatherTools amapWeatherTools;
    private final XhsNoteHotelFoodTools hotelFoodTools;

    public XhsNoteMapResearchService(
        AiStructuredOutputService structuredOutputService,
        PromptResourceService promptResourceService,
        XhsNotePoiEnrichmentService poiEnrichmentService,
        AmapWeatherTools amapWeatherTools,
        XhsNoteHotelFoodTools hotelFoodTools
    ) {
        this.structuredOutputService = structuredOutputService;
        this.promptResourceService = promptResourceService;
        this.poiEnrichmentService = poiEnrichmentService;
        this.amapWeatherTools = amapWeatherTools;
        this.hotelFoodTools = hotelFoodTools;
    }

    /**
     * 执行指定笔记模式的全部高德研究，并合并成 Planner 可消费的地图上下文。
     */
    public MapPlanningContext research(
        String taskId,
        TripRequest trip,
        XhsNoteUnderstandingResult understanding,
        TripResearchProgressReporter reporter
    ) {
        // 阶段 1：Service 逐项校验模型识别出的地点，并补齐高德名称、地址、坐标、评分和图片。
        reporter.report(
            TripTaskStage.AMAP_POI_SEARCH,
            TripTaskProgress.AMAP_POI_SEARCH,
            TripTaskMessages.XHS_NOTE_POI_VALIDATE
        );
        MapPlanningContext poiContext = poiEnrichmentService.enrich(taskId, trip, understanding);

        // 阶段 2：天气是行程日期相关数据，继续复用自主规划中的 Weather Agent 和 Tool。
        reporter.report(
            TripTaskStage.WEATHER_SEARCH,
            TripTaskProgress.WEATHER_SEARCH,
            TripTaskMessages.WEATHER_SEARCH
        );
        MapPlanningContext weatherContext = requireMapContext(
            taskId,
            "高德天气",
            callWeatherAgent(taskId, trip)
        );

        // 只有笔记没有覆盖全部住宿或三餐时才调用补充 Agent，避免覆盖笔记里的明确推荐。
        boolean missingHotel = understanding.needsHotelSupplement(trip.safeTravelDays());
        boolean missingMeals = understanding.needsMealSupplement(trip.safeTravelDays());
        MapPlanningContext supplementContext = null;
        if (missingHotel || missingMeals) {
            // 阶段 3：把当天首尾景点及次日首个景点作为位置锚点，让推荐更贴近日程动线。
            reporter.report(
                TripTaskStage.HOTEL_SEARCH,
                TripTaskProgress.HOTEL_SEARCH,
                TripTaskMessages.XHS_NOTE_HOTEL_FOOD
            );
            supplementContext = requireMapContext(
                taskId,
                "高德酒店餐饮补充",
                callHotelFoodAgent(taskId, trip, understanding, missingHotel, missingMeals)
            );
            requireSupplementCategories(supplementContext, missingHotel, missingMeals);
        } else {
            log.info("[XHS-NOTE-MAP] 笔记已有酒店和餐饮信息，跳过补充 taskId={}", taskId);
        }

        // POI Service、天气 Agent 和可选的酒店餐饮 Agent 按城市合并为 Planner 使用的一份上下文。
        MapPlanningContext merged = mergeMapContexts(trip, poiContext, weatherContext, supplementContext);
        log.info("[XHS-NOTE-MAP] 高德研究完成 taskId={} cities={} attractions={} hotels={} restaurants={} weather={}",
            taskId,
            merged.safeCities().size(),
            count(merged, MapCityContext::safeAttractions),
            count(merged, MapCityContext::safeHotels),
            count(merged, MapCityContext::safeRestaurants),
            merged.safeCities().stream().mapToInt(city -> city.safeWeatherForecasts().size()).sum());
        return merged;
    }

    /** 调用现有天气 Agent；它只能看到天气 Tool，不会在此阶段查询酒店或景点。 */
    private Optional<MapAgentResult> callWeatherAgent(String taskId, TripRequest trip) {
        return callAgent(
            taskId,
            TripstarAgent.AMAP_WEATHER_RESEARCH,
            TripstarPrompt.RESEARCH_AMAP_WEATHER_SYSTEM,
            TripstarPrompt.RESEARCH_AMAP_WEATHER_USER,
            "xhs-note-weather",
            new LinkedHashMap<>(TripPlannerPrompts.requestVariables(trip)),
            amapWeatherTools
        );
    }

    /**
     * 调用酒店餐饮补充 Agent，并明确告诉它当前究竟缺酒店、缺三餐还是两者都缺。
     */
    private Optional<MapAgentResult> callHotelFoodAgent(
        String taskId,
        TripRequest trip,
        XhsNoteUnderstandingResult understanding,
        boolean missingHotel,
        boolean missingMeals
    ) {
        Map<String, String> variables = new LinkedHashMap<>(TripPlannerPrompts.requestVariables(trip));
        variables.put(TripstarPromptVariable.MISSING_HOTEL, String.valueOf(missingHotel));
        variables.put(TripstarPromptVariable.MISSING_MEALS, String.valueOf(missingMeals));
        // routeAnchors 把每日路线压缩成位置锚点，减少提示词长度，同时保留“住哪里最顺路”的依据。
        variables.put(TripstarPromptVariable.ROUTE_ANCHORS, routeAnchors(understanding));
        log.info("[XHS-NOTE-MAP] 准备调用酒店餐饮 Agent taskId={} missingHotel={} missingMeals={}",
            taskId, missingHotel, missingMeals);
        return callAgent(
            taskId,
            TripstarAgent.XHS_NOTE_HOTEL_FOOD,
            TripstarPrompt.XHS_NOTE_HOTEL_FOOD_SYSTEM,
            TripstarPrompt.XHS_NOTE_HOTEL_FOOD_USER,
            "xhs-note-hotel-food",
            variables,
            hotelFoodTools
        );
    }

    /**
     * 三类地图 Agent 的公共调用模板。
     *
     * <p>这里统一加载资源提示词、注入 Structured Output 格式、绑定本阶段 Tool，并使用独立
     * threadId 保存完整提示词和输出日志。传入的 {@code tools} 不同，Agent 能调用的能力也不同。
     */
    private Optional<MapAgentResult> callAgent(
        String taskId,
        TripstarAgent agent,
        String systemPromptPath,
        String userPromptPath,
        String threadSuffix,
        Map<String, String> variables,
        Object tools
    ) {
        // 地图类 Agent 只输出 map_context、tool_calls 和 summary，不携带小红书或用户约束字段。
        variables.put(TripstarPromptVariable.FORMAT, structuredOutputService.format(MapAgentResult.class));
        String userPrompt = promptResourceService.render(userPromptPath, variables);
        log.info("[XHS-NOTE-MAP] 开始调用 Agent taskId={} agent={} tool={}",
            taskId, agent.id(), tools.getClass().getSimpleName());
        // AiStructuredOutputService 内部创建 ReactAgent；最后一个参数注册当前阶段允许使用的 Tool。
        Optional<MapAgentResult> result = structuredOutputService.callForObject(
            agent,
            MapAgentResult.class,
            promptResourceService.load(systemPromptPath),
            userPrompt,
            taskId + "-" + threadSuffix,
            tools
        );
        result.ifPresent(value -> log.info(
            "[XHS-NOTE-MAP] Agent 完成 taskId={} agent={} realData={} cities={} toolCalls={} summary={}",
            taskId,
            agent.id(),
            value.map_context() != null && value.map_context().realData(),
            value.map_context() == null ? 0 : value.map_context().safeCities().size(),
            value.safeToolCalls(),
            value.safeSummary()
        ));
        return result;
    }

    /**
     * 对 Agent 返回值做严格校验。
     *
     * <p>这里不使用模拟数据或静默兜底：结构化结果缺失、map_context 缺失、真实数据标记失败
     * 都立即终止任务，并把准确原因推送给前端。
     */
    private MapPlanningContext requireMapContext(
        String taskId,
        String label,
        Optional<MapAgentResult> optionalResult
    ) {
        MapAgentResult result = optionalResult
            .orElseThrow(() -> new BizException(label + " Agent 未返回结构化结果。"));
        MapPlanningContext context = result.map_context();
        if (context == null) {
            throw new BizException(label + " Agent 输出缺少 map_context。");
        }
        return validateMapContext(taskId, label, context);
    }

    /** 统一检查地图阶段是否真正返回了高德数据。 */
    private MapPlanningContext validateMapContext(String taskId, String label, MapPlanningContext context) {
        if (!context.realData()) {
            throw new BizException(label + "失败：" + safe(context.message()));
        }
        log.info("[XHS-NOTE-MAP] 阶段数据校验成功 taskId={} stage={} cities={} message={}",
            taskId, label, context.safeCities().size(), context.message());
        return context;
    }

    /** 确认补充 Agent 真的返回了请求中缺少的类别，而不只是返回一段成功说明。 */
    private void requireSupplementCategories(
        MapPlanningContext context,
        boolean missingHotel,
        boolean missingMeals
    ) {
        boolean hasHotels = context.safeCities().stream().anyMatch(city -> !city.safeHotels().isEmpty());
        boolean hasRestaurants = context.safeCities().stream().anyMatch(city -> !city.safeRestaurants().isEmpty());
        if (missingHotel && !hasHotels) {
            throw new BizException("笔记缺少酒店，但高德酒店 Agent 没有返回酒店候选。");
        }
        if (missingMeals && !hasRestaurants) {
            throw new BizException("笔记缺少餐饮，但高德餐饮 Agent 没有返回餐饮候选。");
        }
    }

    /**
     * 按城市合并 POI、天气及可选的酒店餐饮上下文。
     *
     * <p>{@link LinkedHashMap} 保持 TripRequest 中的城市顺序，避免多城市计划在合并后乱序。
     */
    private MapPlanningContext mergeMapContexts(
        TripRequest trip,
        MapPlanningContext poiContext,
        MapPlanningContext weatherContext,
        MapPlanningContext supplementContext
    ) {
        Map<String, MapCityAccumulator> cities = new LinkedHashMap<>();
        // 先放入请求中的所有城市，即使某个阶段没有返回该城市，也不会影响其他阶段后续合并。
        trip.normalizedCities().forEach(city -> cities.put(city.city(), new MapCityAccumulator(city.city())));
        mergeStage(cities, poiContext);
        mergeStage(cities, weatherContext);
        mergeStage(cities, supplementContext);

        List<MapCityContext> mergedCities = cities.values().stream()
            .map(MapCityAccumulator::toContext)
            // 最终仍没有任何地图数据的城市不交给 Planner，防止产生空城市块。
            .filter(MapCityContext::hasAnyData)
            .toList();
        String message = "指定笔记高德研究完成：POI Service=" + safe(poiContext.message())
            + "；天气=" + safe(weatherContext.message())
            + "；酒店餐饮=" + (supplementContext == null ? "笔记信息完整，未补充" : safe(supplementContext.message()));
        return new MapPlanningContext(
            mergedCities,
            !mergedCities.isEmpty(),
            TravelDataSource.AMAP,
            message
        );
    }

    /** 把一个阶段的城市数据追加到累加器，允许 Agent 返回请求之外但有效的城市名称。 */
    private void mergeStage(Map<String, MapCityAccumulator> target, MapPlanningContext context) {
        if (context == null) {
            return;
        }
        for (MapCityContext city : context.safeCities()) {
            if (city == null || city.city() == null || city.city().isBlank()) {
                continue;
            }
            target.computeIfAbsent(city.city(), MapCityAccumulator::new).merge(city);
        }
    }

    /**
     * 提取每天首个、中间、最后景点以及次日首个景点，供酒店餐饮 Agent 判断就近位置。
     */
    private String routeAnchors(XhsNoteUnderstandingResult understanding) {
        if (understanding.safeDayRoutes().isEmpty()) {
            return "笔记没有明确每日路线，请根据已识别地点和用户要求选择交通方便的区域。";
        }
        StringBuilder builder = new StringBuilder();
        List<XhsNoteDayRoute> routes = understanding.safeDayRoutes();
        for (int i = 0; i < routes.size(); i++) {
            XhsNoteDayRoute route = routes.get(i);
            List<String> places = route.safePlaces();
            // 当天最后一个景点影响晚餐和住宿，次日首个景点影响住宿是否方便第二天出发。
            String first = places.isEmpty() ? "未知" : places.getFirst();
            String middle = places.isEmpty() ? "未知" : places.get(places.size() / 2);
            String last = places.isEmpty() ? "未知" : places.getLast();
            String nextFirst = i + 1 >= routes.size() || routes.get(i + 1).safePlaces().isEmpty()
                ? "无"
                : routes.get(i + 1).safePlaces().getFirst();
            builder.append("Day").append(route.day() == null ? i + 1 : route.day())
                .append(": first=").append(first)
                .append(", middle=").append(middle)
                .append(", last=").append(last)
                .append(", nextDayFirst=").append(nextFirst)
                .append('\n');
        }
        return builder.toString().trim();
    }

    /** 统计所有城市中某一类 POI 的数量，仅用于阶段完成日志。 */
    private int count(
        MapPlanningContext context,
        java.util.function.Function<MapCityContext, List<MapPoi>> extractor
    ) {
        return context.safeCities().stream().mapToInt(city -> extractor.apply(city).size()).sum();
    }

    /** 为阶段汇总日志和错误消息提供可读的空值说明。 */
    private String safe(String value) {
        return value == null || value.isBlank() ? "未返回说明" : value;
    }

    /**
     * 单城市的合并容器。使用可变集合完成阶段合并，最后再转换成不可变 DTO。
     */
    private static final class MapCityAccumulator {

        private final String city;
        private MapPoint center;
        private final List<MapPoi> attractions = new ArrayList<>();
        private final List<MapPoi> hotels = new ArrayList<>();
        private final List<MapPoi> restaurants = new ArrayList<>();
        private final List<MapWeatherForecast> weather = new ArrayList<>();

        private MapCityAccumulator(String city) {
            this.city = city;
        }

        /** 合并一个阶段返回的同城数据；中心点只取第一个有效值。 */
        private void merge(MapCityContext context) {
            if (center == null && context.center() != null && context.center().available()) {
                center = context.center();
            }
            attractions.addAll(context.safeAttractions());
            hotels.addAll(context.safeHotels());
            restaurants.addAll(context.safeRestaurants());
            weather.addAll(context.safeWeatherForecasts());
        }

        /** 冻结累加结果，避免返回后仍被后续代码修改。 */
        private MapCityContext toContext() {
            return new MapCityContext(
                city,
                center,
                List.copyOf(attractions),
                List.copyOf(hotels),
                List.copyOf(restaurants),
                List.copyOf(weather)
            );
        }
    }
}
