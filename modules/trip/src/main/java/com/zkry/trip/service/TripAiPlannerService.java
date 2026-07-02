package com.zkry.trip.service;

import com.zkry.ai.service.AiTextService;
import com.zkry.ai.service.LlmJsonExtractor;
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
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TripAiPlannerService {

    private static final Logger log = LoggerFactory.getLogger(TripAiPlannerService.class);

    private final AiTextService aiTextService;

    public TripAiPlannerService(AiTextService aiTextService) {
        this.aiTextService = aiTextService;
    }

    public boolean isAvailable() {
        return aiTextService.isAvailable();
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
        String prompt = TripPlannerPrompts.plannerPrompt(request, mapContext, contentContext);
        log.info("[AI-PLAN] 开始行程规划 planId={} city={} days={} contentRealData={} contentCities={} mapRealData={} mapCities={}",
            planId,
            request.primaryCity(),
            request.safeTravelDays(),
            contentContext != null && contentContext.realData(),
            contentContext == null ? 0 : contentContext.safeCities().size(),
            mapContext != null && mapContext.realData(),
            mapContext == null ? 0 : mapContext.safeCities().size());

        // LLM 只负责生成 TripPlan JSON；图谱结构和前端兼容字段继续复用 Java 侧转换。
        Optional<String> response = aiTextService.generate(
            TripPlannerPrompts.systemPrompt(),
            prompt
        );
        if (response.isEmpty()) {
            log.info("[AI-PLAN] LLM 未返回规划内容 planId={} elapsedMs={}",
                planId, System.currentTimeMillis() - startedAt);
            return Optional.empty();
        }
        List<String> candidates = LlmJsonExtractor.extractJsonObjectCandidates(response.get());
        log.info("[AI-PLAN] LLM 返回规划内容 planId={} responseLength={} jsonCandidates={}",
            planId, response.get().length(), candidates.size());
        int index = 0;
        for (String json : candidates) {
            index++;
            try {
                TripPlan plan = JsonUtils.parseObject(json, TripPlan.class);
                if (plan != null) {
                    TripPlan normalized = normalize(plan, request);
                    log.info("[AI-PLAN] 行程 JSON 解析成功 planId={} candidateIndex={} days={} cities={} elapsedMs={}",
                        planId,
                        index,
                        normalized.days() == null ? 0 : normalized.days().size(),
                        normalized.cities() == null ? 0 : normalized.cities().size(),
                        System.currentTimeMillis() - startedAt);
                    return Optional.of(TripPlanResponseFactory.fromPlan(planId, normalized));
                }
            } catch (Exception ex) {
                log.debug("[AI-PLAN] 行程 JSON 候选解析失败 planId={} candidateIndex={} reason={}",
                    planId, index, ex.getMessage());
            }
        }
        log.warn("[AI-PLAN] LLM 行程 JSON 解析失败 planId={} elapsedMs={}",
            planId, System.currentTimeMillis() - startedAt);
        return Optional.empty();
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
}
