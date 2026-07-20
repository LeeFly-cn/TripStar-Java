package com.zkry.trip.service;

import com.zkry.map.dto.MapCityContext;
import com.zkry.map.dto.MapPlanningContext;
import com.zkry.map.dto.MapPoi;
import com.zkry.trip.dto.Attraction;
import com.zkry.trip.dto.DayPlan;
import com.zkry.trip.dto.TripPlan;
import com.zkry.trip.dto.TripPlanResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 把指定笔记 POI Service 已经取得的高德图片写回最终旅行计划。
 *
 * <p>图片 URL 属于确定性展示数据，不需要再让 Planner Agent 复制。这里按城市和景点名称
 * 匹配 {@link MapPoi#photoUrl()}，只填充原本为空的 {@code image_url}。这样大部分图片无需
 * 结果页再次请求高德，只有未匹配或高德未返回图片的景点才走后续 HTTP 补查。
 */
@Component
public class XhsNotePlanPhotoEnricher {

    private static final Logger log = LoggerFactory.getLogger(XhsNotePlanPhotoEnricher.class);

    /** 返回一份带高德景点图片的新响应，不修改原 record。 */
    public TripPlanResponse enrich(TripPlanResponse response, MapPlanningContext mapContext) {
        if (response == null || response.data() == null || mapContext == null) {
            return response;
        }

        List<PhotoCandidate> candidates = photoCandidates(mapContext);
        if (candidates.isEmpty()) {
            log.info("[XHS-NOTE-PHOTO] POI 上下文没有可用图片 planId={}", response.plan_id());
            return response;
        }

        TripPlan plan = response.data();
        int missingBefore = countMissingImages(plan);
        List<DayPlan> days = safeDays(plan).stream()
            .map(day -> enrichDay(day, plan.city(), candidates))
            .toList();
        TripPlan enrichedPlan = new TripPlan(
            plan.city(),
            plan.cities(),
            plan.start_date(),
            plan.end_date(),
            days,
            plan.weather_info(),
            plan.overall_suggestions(),
            plan.budget()
        );
        int enrichedCount = missingBefore - countMissingImages(enrichedPlan);
        log.info("[XHS-NOTE-PHOTO] 已复用 POI 阶段图片 planId={} candidates={} enriched={}",
            response.plan_id(), candidates.size(), enrichedCount);
        return new TripPlanResponse(
            response.success(),
            response.message(),
            response.plan_id(),
            enrichedPlan,
            response.graph_data()
        );
    }

    private DayPlan enrichDay(
        DayPlan day,
        String primaryCity,
        List<PhotoCandidate> candidates
    ) {
        if (day == null) {
            return null;
        }
        String city = isBlank(day.city()) ? primaryCity : day.city();
        List<Attraction> attractions = safeAttractions(day).stream()
            .map(attraction -> enrichAttraction(attraction, city, candidates))
            .toList();
        return new DayPlan(
            day.date(),
            day.day_index(),
            day.city(),
            day.is_transfer_day(),
            day.transfer_info(),
            day.description(),
            day.transportation(),
            day.accommodation(),
            day.hotel(),
            attractions,
            day.meals()
        );
    }

    private Attraction enrichAttraction(
        Attraction attraction,
        String city,
        List<PhotoCandidate> candidates
    ) {
        if (attraction == null || !isBlank(attraction.image_url())) {
            return attraction;
        }
        String photoUrl = findPhoto(city, attraction.name(), candidates);
        if (photoUrl.isBlank()) {
            return attraction;
        }
        return new Attraction(
            attraction.name(),
            attraction.address(),
            attraction.location(),
            attraction.visit_duration(),
            attraction.description(),
            attraction.category(),
            attraction.rating(),
            photoUrl,
            attraction.ticket_price()
        );
    }

    /** 优先同城精确匹配，再逐步放宽到名称包含和跨城市匹配。 */
    private String findPhoto(String city, String name, List<PhotoCandidate> candidates) {
        String normalizedCity = normalize(city);
        String normalizedName = normalize(name);
        if (normalizedName.isBlank()) {
            return "";
        }
        for (int score = 0; score <= 3; score++) {
            for (PhotoCandidate candidate : candidates) {
                if (matchScore(normalizedCity, normalizedName, candidate) == score) {
                    return candidate.photoUrl();
                }
            }
        }
        return "";
    }

    private int matchScore(String city, String name, PhotoCandidate candidate) {
        boolean sameCity = city.equals(candidate.city());
        boolean sameName = name.equals(candidate.name());
        boolean similarName = name.contains(candidate.name()) || candidate.name().contains(name);
        if (sameCity && sameName) return 0;
        if (sameCity && similarName) return 1;
        if (sameName) return 2;
        return similarName ? 3 : Integer.MAX_VALUE;
    }

    private List<PhotoCandidate> photoCandidates(MapPlanningContext mapContext) {
        List<PhotoCandidate> result = new ArrayList<>();
        for (MapCityContext city : mapContext.safeCities()) {
            for (MapPoi poi : city.safeAttractions()) {
                if (poi == null || isBlank(poi.name()) || isBlank(poi.photoUrl())) {
                    continue;
                }
                result.add(new PhotoCandidate(normalize(city.city()), normalize(poi.name()), poi.photoUrl()));
            }
        }
        return List.copyOf(result);
    }

    private List<DayPlan> safeDays(TripPlan plan) {
        return plan.days() == null ? List.of() : plan.days();
    }

    private List<Attraction> safeAttractions(DayPlan day) {
        return day.attractions() == null ? List.of() : day.attractions();
    }

    /** 统计还没有 image_url 的景点，用于日志展示本次实际补充数量。 */
    private int countMissingImages(TripPlan plan) {
        return safeDays(plan).stream()
            .filter(day -> day != null)
            .flatMap(day -> safeAttractions(day).stream())
            .filter(attraction -> attraction != null && isBlank(attraction.image_url()))
            .mapToInt(attraction -> 1)
            .sum();
    }

    private String normalize(String value) {
        return value == null
            ? ""
            : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record PhotoCandidate(String city, String name, String photoUrl) {
    }
}
