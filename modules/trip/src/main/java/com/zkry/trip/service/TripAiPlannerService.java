package com.zkry.trip.service;

import com.zkry.ai.agent.TripstarAgent;
import com.zkry.ai.prompt.TripstarPrompt;
import com.zkry.ai.prompt.TripstarPromptVariable;
import com.zkry.ai.service.AiAgentService;
import com.zkry.ai.service.AiStructuredOutputService;
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
        long startedAt = System.currentTimeMillis();
        String systemPrompt = promptResourceService.load(TripstarPrompt.PLANNER_SYSTEM);
        Map<String, String> variables = new java.util.LinkedHashMap<>(TripPlannerPrompts.plannerVariables(request, mapContext, contentContext));
        variables.put(TripstarPromptVariable.FORMAT, structuredOutputService.format(TripPlan.class));
        String prompt = promptResourceService.render(TripstarPrompt.PLANNER_USER, variables);
        log.info("[AI-PLAN] 开始行程规划 planId={} city={} days={} contentRealData={} contentCities={} mapRealData={} mapCities={}",
            planId,
            request.primaryCity(),
            request.safeTravelDays(),
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

    private boolean reviewPlan(String planId, TripRequest request, TripPlan plan) {
        String systemPrompt = promptResourceService.load(TripstarPrompt.REVIEW_SYSTEM);
        String userPrompt = promptResourceService.render(
            TripstarPrompt.REVIEW_USER,
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
        log.info("[AI-REVIEW] ReviewAgent 完成 planId={} passed={} issues={}",
            planId, result.passed(), result.safeIssues().size());
        if (!result.passed()) {
            log.warn("[AI-REVIEW] 行程质检未通过 planId={} issues={}", planId, result.safeIssues());
        }
        return result.passed();
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
