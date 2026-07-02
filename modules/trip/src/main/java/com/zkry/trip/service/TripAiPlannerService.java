package com.zkry.trip.service;

import com.zkry.ai.service.AiAgentService;
import com.zkry.ai.service.LlmJsonExtractor;
import com.zkry.ai.service.PromptResourceService;
import com.zkry.common.json.utils.JsonUtils;
import com.zkry.content.dto.ContentPlanningContext;
import com.zkry.map.dto.MapPlanningContext;
import com.zkry.trip.dto.Budget;
import com.zkry.trip.dto.CityStay;
import com.zkry.trip.dto.DayPlan;
import com.zkry.trip.dto.TripPlan;
import com.zkry.trip.dto.TripPlanResponse;
import com.zkry.trip.dto.TripRequest;
import com.zkry.trip.prompt.TripPlannerPrompts;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TripAiPlannerService {

    private static final Logger log = LoggerFactory.getLogger(TripAiPlannerService.class);

    private final AiAgentService aiAgentService;
    private final PromptResourceService promptResourceService;

    public TripAiPlannerService(
        AiAgentService aiAgentService,
        PromptResourceService promptResourceService
    ) {
        this.aiAgentService = aiAgentService;
        this.promptResourceService = promptResourceService;
    }

    public boolean isAvailable() {
        return aiAgentService.isAvailable();
    }

    public Optional<TripPlanResponse> plan(String planId, TripRequest request) {
        return plan(planId, request, MapPlanningContext.empty("none", "未采集地图上下文。"));
    }

    public Optional<TripPlanResponse> plan(String planId, TripRequest request, MapPlanningContext mapContext) {
        return plan(planId, request, mapContext, ContentPlanningContext.empty("none", "未采集游记内容上下文。"));
    }

    public Optional<TripPlanResponse> plan(
        String planId,
        TripRequest request,
        MapPlanningContext mapContext,
        ContentPlanningContext contentContext
    ) {
        long startedAt = System.currentTimeMillis();
        String systemPrompt = promptResourceService.load(TripPlannerPrompts.PLANNER_SYSTEM);
        String prompt = promptResourceService.render(
            TripPlannerPrompts.PLANNER_USER,
            TripPlannerPrompts.plannerVariables(request, mapContext, contentContext)
        );
        log.info("[AI-PLAN] 开始行程规划 planId={} city={} days={} contentRealData={} contentCities={} mapRealData={} mapCities={}",
            planId,
            request.primaryCity(),
            request.safeTravelDays(),
            contentContext != null && contentContext.realData(),
            contentContext == null ? 0 : contentContext.safeCities().size(),
            mapContext != null && mapContext.realData(),
            mapContext == null ? 0 : mapContext.safeCities().size());

        Optional<String> response = aiAgentService.call(
            "trip-planner-agent",
            systemPrompt,
            prompt,
            planId + "-planner"
        );
        if (response.isEmpty()) {
            log.info("[AI-PLAN] TripPlannerAgent 未返回规划内容 planId={} elapsedMs={}",
                planId, System.currentTimeMillis() - startedAt);
            return Optional.empty();
        }
        Optional<TripPlan> parsedPlan = parsePlan(planId, request, response.get(), "planner");
        if (parsedPlan.isEmpty()) {
            parsedPlan = repairPlan(planId, request, response.get());
        }
        if (parsedPlan.isEmpty()) {
            log.warn("[AI-PLAN] TripPlan JSON 解析和修复均失败 planId={} elapsedMs={}",
                planId, System.currentTimeMillis() - startedAt);
            return Optional.empty();
        }

        TripPlan normalized = normalize(parsedPlan.get(), request);
        if (!reviewPlan(planId, request, normalized)) {
            return Optional.empty();
        }
        log.info("[AI-PLAN] 多 Agent 行程规划完成 planId={} days={} cities={} elapsedMs={}",
            planId,
            normalized.days() == null ? 0 : normalized.days().size(),
            normalized.cities() == null ? 0 : normalized.cities().size(),
            System.currentTimeMillis() - startedAt);
        return Optional.of(TripPlanResponseFactory.fromPlan(planId, normalized));
    }

    private Optional<TripPlan> parsePlan(String planId, TripRequest request, String rawResponse, String source) {
        List<String> candidates = LlmJsonExtractor.extractJsonObjectCandidates(rawResponse);
        log.info("[AI-PLAN] {} 返回规划内容 planId={} responseLength={} jsonCandidates={}",
            source, planId, rawResponse == null ? 0 : rawResponse.length(), candidates.size());
        int index = 0;
        for (String json : candidates) {
            index++;
            try {
                TripPlan plan = JsonUtils.parseObject(json, TripPlan.class);
                if (plan != null) {
                    log.info("[AI-PLAN] 行程 JSON 解析成功 planId={} source={} candidateIndex={}",
                        planId,
                        source,
                        index);
                    return Optional.of(plan);
                }
            } catch (Exception ex) {
                log.debug("[AI-PLAN] 行程 JSON 候选解析失败 planId={} source={} candidateIndex={} reason={}",
                    planId, source, index, ex.getMessage());
            }
        }
        return Optional.empty();
    }

    private Optional<TripPlan> repairPlan(String planId, TripRequest request, String rawResponse) {
        String systemPrompt = promptResourceService.load(TripPlannerPrompts.JSON_REPAIR_SYSTEM);
        String userPrompt = promptResourceService.render(
            TripPlannerPrompts.JSON_REPAIR_USER,
            Map.of("raw_response", rawResponse == null ? "" : rawResponse)
        );
        Optional<String> repaired = aiAgentService.call(
            "json-repair-agent",
            systemPrompt,
            userPrompt,
            planId + "-repair"
        );
        if (repaired.isEmpty()) {
            return Optional.empty();
        }
        return parsePlan(planId, request, repaired.get(), "repair");
    }

    private boolean reviewPlan(String planId, TripRequest request, TripPlan plan) {
        String systemPrompt = promptResourceService.load(TripPlannerPrompts.REVIEW_SYSTEM);
        String userPrompt = promptResourceService.render(
            TripPlannerPrompts.REVIEW_USER,
            Map.of(
                "travel_days", String.valueOf(request.safeTravelDays()),
                "city_names", String.join("、", plan.cities() == null ? List.of() : plan.cities()),
                "trip_plan_json", JsonUtils.toJsonString(plan)
            )
        );
        Optional<String> response = aiAgentService.call(
            "trip-review-agent",
            systemPrompt,
            userPrompt,
            planId + "-review"
        );
        if (response.isEmpty()) {
            log.warn("[AI-REVIEW] ReviewAgent 未返回结果 planId={}", planId);
            return false;
        }
        for (String json : LlmJsonExtractor.extractJsonObjectCandidates(response.get())) {
            try {
                ReviewResult result = JsonUtils.parseObject(json, ReviewResult.class);
                if (result != null) {
                    log.info("[AI-REVIEW] ReviewAgent 完成 planId={} passed={} issues={}",
                        planId, result.passed(), result.safeIssues().size());
                    if (!result.passed()) {
                        log.warn("[AI-REVIEW] 行程质检未通过 planId={} issues={}", planId, result.safeIssues());
                    }
                    return result.passed();
                }
            } catch (Exception ex) {
                log.debug("[AI-REVIEW] ReviewAgent JSON 解析失败 planId={} reason={}", planId, ex.getMessage());
            }
        }
        log.warn("[AI-REVIEW] ReviewAgent 输出不可解析 planId={}", planId);
        return false;
    }

    private TripPlan normalize(TripPlan plan, TripRequest request) {
        List<String> cities = plan.cities();
        if (cities == null || cities.isEmpty()) {
            cities = request.normalizedCities().stream().map(CityStay::city).toList();
        }
        String city = isBlank(plan.city())
            ? (cities.isEmpty() ? request.primaryCity() : cities.getFirst())
            : plan.city();
        List<DayPlan> days = plan.days() == null ? List.of() : plan.days();
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
