package com.zkry.trip.service;

import com.zkry.ai.agent.TripstarAgent;
import com.zkry.ai.prompt.TripstarPrompt;
import com.zkry.ai.prompt.TripstarPromptVariable;
import com.zkry.ai.service.AiAgentService;
import com.zkry.ai.service.AiStructuredOutputService;
import com.zkry.ai.service.PromptResourceService;
import com.zkry.common.core.exception.BizException;
import com.zkry.common.json.utils.JsonUtils;
import com.zkry.content.dto.ContentPlanningContext;
import com.zkry.map.dto.MapCityContext;
import com.zkry.map.dto.MapPlanningContext;
import com.zkry.map.dto.MapPoi;
import com.zkry.trip.dto.Attraction;
import com.zkry.trip.dto.Budget;
import com.zkry.trip.dto.CityStay;
import com.zkry.trip.dto.DayPlan;
import com.zkry.trip.dto.TripPlan;
import com.zkry.trip.dto.TripPlanResponse;
import com.zkry.trip.dto.TripRequest;
import com.zkry.trip.prompt.TripPlannerPrompts;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 行程规划 Agent 编排服务。
 *
 * <p>Research 阶段只负责查资料；这个类负责把用户需求、地图上下文、小红书上下文
 * 交给 PlannerAgent 生成 {@link TripPlan}，再交给 ReviewAgent 做结构检查。
 * LLM 输出不再手写扒 JSON，而是通过 {@link AiStructuredOutputService} 转成 DTO。
 */
@Service
public class TripAiPlannerService {

    private static final Logger log = LoggerFactory.getLogger(TripAiPlannerService.class);

    private final AiAgentService aiAgentService;
    private final AiStructuredOutputService structuredOutputService;
    private final PromptResourceService promptResourceService;

    public TripAiPlannerService(
        AiAgentService aiAgentService,
        AiStructuredOutputService structuredOutputService,
        PromptResourceService promptResourceService
    ) {
        this.aiAgentService = aiAgentService;
        this.structuredOutputService = structuredOutputService;
        this.promptResourceService = promptResourceService;
    }

    public boolean isAvailable() {
        return aiAgentService.isAvailable();
    }

    public Optional<TripPlanResponse> plan(
        String planId,
        TripRequest request,
        MapPlanningContext mapContext,
        ContentPlanningContext contentContext
    ) {
        return plan(planId, request, mapContext, contentContext, false);
    }

    /**
     * 指定笔记规划入口。
     *
     * <p>它继续复用同一个 PlannerAgent 和 ReviewAgent，但使用指定笔记专属用户提示词：
     * 小红书笔记中已经确认的景点全部传入并要求完整保留，不套用自主规划的每日数量限制。
     */
    public Optional<TripPlanResponse> planFromXhsNotes(
        String planId,
        TripRequest request,
        MapPlanningContext mapContext,
        ContentPlanningContext contentContext
    ) {
        return plan(planId, request, mapContext, contentContext, true);
    }

    private Optional<TripPlanResponse> plan(
        String planId,
        TripRequest request,
        MapPlanningContext mapContext,
        ContentPlanningContext contentContext,
        boolean xhsNoteMode
    ) {
        long startedAt = System.currentTimeMillis();
        String systemPrompt = promptResourceService.load(TripstarPrompt.PLANNER_SYSTEM);
        Map<String, String> variables = new LinkedHashMap<>(
            xhsNoteMode
                ? TripPlannerPrompts.xhsNotePlannerVariables(request, mapContext, contentContext)
                : TripPlannerPrompts.plannerVariables(request, mapContext, contentContext)
        );
        variables.put(TripstarPromptVariable.FORMAT, structuredOutputService.format(TripPlan.class));
        String userPromptPath = xhsNoteMode
            ? TripstarPrompt.XHS_NOTE_PLANNER_USER
            : TripstarPrompt.PLANNER_USER;
        String prompt = promptResourceService.render(userPromptPath, variables);
        log.info("[AI-PLAN] 开始行程规划 planId={} city={} days={} xhsNoteMode={} prompt={} contentRealData={} contentCities={} mapRealData={} mapCities={}",
            planId,
            request.primaryCity(),
            request.safeTravelDays(),
            xhsNoteMode,
            userPromptPath,
            contentContext != null && contentContext.realData(),
            contentContext == null ? 0 : contentContext.safeCities().size(),
            mapContext != null && mapContext.realData(),
            mapContext == null ? 0 : mapContext.safeCities().size());

        Optional<TripPlan> parsedPlan = structuredOutputService.callForObject(
            TripstarAgent.TRIP_PLANNER,
            TripPlan.class,
            systemPrompt,
            prompt,
            planId + "-planner"
        );
        if (parsedPlan.isEmpty()) {
            log.info("[AI-PLAN] TripPlannerAgent 未返回规划内容 planId={} elapsedMs={}",
                planId, System.currentTimeMillis() - startedAt);
            return Optional.empty();
        }

        TripPlan normalized = normalize(planId, parsedPlan.get(), request);
        if (xhsNoteMode) {
            requireAllXhsNoteAttractions(planId, mapContext, normalized);
        }
        if (!reviewPlan(planId, request, normalized, xhsNoteMode)) {
            return Optional.empty();
        }
        log.info("[AI-PLAN] 多 Agent 行程规划完成 planId={} days={} cities={} elapsedMs={}",
            planId,
            normalized.days() == null ? 0 : normalized.days().size(),
            normalized.cities() == null ? 0 : normalized.cities().size(),
            System.currentTimeMillis() - startedAt);
        return Optional.of(TripPlanResponseFactory.fromPlan(planId, normalized));
    }

    /**
     * 指定笔记模式不允许 Planner 静默删减用户笔记里的景点。
     *
     * <p>高德 POI 补全后的景点名称是 Planner 的最终数据契约。Prompt 已要求全部保留，
     * 这里再做一次确定性校验：模型少返回任何一个景点都立即失败，并在日志和异常中列出缺失项。
     */
    private void requireAllXhsNoteAttractions(
        String planId,
        MapPlanningContext mapContext,
        TripPlan plan
    ) {
        Map<String, String> expectedNames = new LinkedHashMap<>();
        if (mapContext != null) {
            for (MapCityContext city : mapContext.safeCities()) {
                for (MapPoi poi : city.safeAttractions()) {
                    String normalizedName = normalizePlaceName(poi.name());
                    if (!normalizedName.isEmpty()) {
                        expectedNames.putIfAbsent(normalizedName, poi.name());
                    }
                }
            }
        }

        Set<String> actualNames = new LinkedHashSet<>();
        for (DayPlan day : plan.days() == null ? List.<DayPlan>of() : plan.days()) {
            for (Attraction attraction : day.attractions() == null ? List.<Attraction>of() : day.attractions()) {
                String normalizedName = normalizePlaceName(attraction.name());
                if (!normalizedName.isEmpty()) {
                    actualNames.add(normalizedName);
                }
            }
        }

        List<String> missingNames = expectedNames.entrySet().stream()
            .filter(entry -> !actualNames.contains(entry.getKey()))
            .map(Map.Entry::getValue)
            .toList();
        log.info("[AI-PLAN] 指定笔记景点完整性校验 planId={} expected={} actual={} missing={}",
            planId, expectedNames.size(), actualNames.size(), missingNames);
        if (!missingNames.isEmpty()) {
            throw new BizException("指定笔记规划未保留全部景点，缺少：" + String.join("、", missingNames));
        }
    }

    /** 比较 POI 名称时只忽略空白，其他字符必须保持高德校准后的正式名称。 */
    private String normalizePlaceName(String name) {
        return name == null ? "" : name.replaceAll("\\s+", "");
    }

    private boolean reviewPlan(
        String planId,
        TripRequest request,
        TripPlan plan,
        boolean xhsNoteMode
    ) {
        String systemPrompt = promptResourceService.load(TripstarPrompt.REVIEW_SYSTEM);
        String userPromptPath = xhsNoteMode
            ? TripstarPrompt.XHS_NOTE_REVIEW_USER
            : TripstarPrompt.REVIEW_USER;
        String userPrompt = promptResourceService.render(
            userPromptPath,
            Map.of(
                TripstarPromptVariable.TRAVEL_DAYS, String.valueOf(request.safeTravelDays()),
                TripstarPromptVariable.CITY_NAMES, String.join("、", plan.cities() == null ? List.of() : plan.cities()),
                TripstarPromptVariable.TRIP_PLAN_JSON, JsonUtils.toJsonString(plan),
                TripstarPromptVariable.FORMAT, structuredOutputService.format(ReviewResult.class)
            )
        );
        Optional<ReviewResult> response = structuredOutputService.callForObject(
            TripstarAgent.TRIP_REVIEW,
            ReviewResult.class,
            systemPrompt,
            userPrompt,
            planId + "-review"
        );
        if (response.isEmpty()) {
            log.warn("[AI-REVIEW] ReviewAgent 未返回结果 planId={}", planId);
            return false;
        }
        ReviewResult result = response.get();
        log.info("[AI-REVIEW] ReviewAgent 完成 planId={} xhsNoteMode={} prompt={} passed={} issues={}",
            planId, xhsNoteMode, userPromptPath, result.passed(), result.safeIssues().size());
        if (!result.passed()) {
            log.warn("[AI-REVIEW] 行程质检未通过 planId={} issues={}", planId, result.safeIssues());
        }
        return result.passed();
    }

    private TripPlan normalize(String planId, TripPlan plan, TripRequest request) {
        List<String> cities = plan.cities();
        if (cities == null || cities.isEmpty()) {
            cities = request.normalizedCities().stream().map(CityStay::city).toList();
        }
        String city = isBlank(plan.city())
            ? (cities.isEmpty() ? request.primaryCity() : cities.getFirst())
            : plan.city();
        List<DayPlan> days = normalizeDays(planId, plan.days());
        Budget budget = plan.budget() == null
            ? new Budget(0, 0, 0, 0, 0, 0)
            : plan.budget();
        return new TripPlan(
            city,
            cities,
            isBlank(plan.start_date()) ? request.start_date() : plan.start_date(),
            isBlank(plan.end_date()) ? request.end_date() : plan.end_date(),
            days,
            plan.weather_info() == null ? List.of() : plan.weather_info(),
            isBlank(plan.overall_suggestions()) ? "AI 已生成行程，建议根据实际营业时间二次确认。" : plan.overall_suggestions(),
            budget
        );
    }

    /**
     * Planner 只负责行程结构，不负责图片来源。
     *
     * <p>模型有时会为了满足必填的 image_url 字段编造 example.com 等地址。这里统一清空：
     * 指定笔记模式随后由 {@link XhsNotePlanPhotoEnricher} 写入高德真实图片，自主规划则由
     * 前端调用小红书图片接口。这样不会再把模型生成的占位 URL 当成真实数据。
     */
    private List<DayPlan> normalizeDays(String planId, List<DayPlan> sourceDays) {
        if (sourceDays == null || sourceDays.isEmpty()) {
            return List.of();
        }
        List<DayPlan> normalizedDays = new ArrayList<>(sourceDays.size());
        int clearedImageUrls = 0;
        for (DayPlan day : sourceDays) {
            List<Attraction> attractions = new ArrayList<>();
            for (Attraction attraction : day.attractions() == null ? List.<Attraction>of() : day.attractions()) {
                if (!isBlank(attraction.image_url())) {
                    clearedImageUrls++;
                }
                attractions.add(new Attraction(
                    attraction.name(),
                    attraction.address(),
                    attraction.location(),
                    attraction.visit_duration(),
                    attraction.description(),
                    attraction.category(),
                    attraction.rating(),
                    "",
                    attraction.ticket_price()
                ));
            }
            normalizedDays.add(new DayPlan(
                day.date(),
                day.day_index(),
                day.city(),
                day.is_transfer_day(),
                day.transfer_info(),
                day.description(),
                day.transportation(),
                day.accommodation(),
                day.hotel(),
                List.copyOf(attractions),
                day.meals()
            ));
        }
        if (clearedImageUrls > 0) {
            log.warn("[AI-PLAN] 已清理模型生成的景点图片 URL planId={} count={}，图片交由真实数据源补充",
                planId, clearedImageUrls);
        }
        return List.copyOf(normalizedDays);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record ReviewResult(
        boolean passed,
        List<String> issues,
        List<String> suggestions
    ) {
        private List<String> safeIssues() {
            return issues == null ? List.of() : issues;
        }
    }
}
