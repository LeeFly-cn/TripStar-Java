package com.zkry.trip.service;

import com.zkry.common.core.exception.BizException;
import com.zkry.trip.dto.CityStay;
import com.zkry.trip.dto.TripRequest;
import com.zkry.trip.dto.xhsnote.XhsNotePlanRequest;
import com.zkry.trip.dto.xhsnote.XhsNoteUnderstandingResult;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 把多模态理解结果转换成现有 Planner Agent 使用的 {@link TripRequest}。
 *
 * <p>这是新旧流程之间最重要的适配点：指定笔记模式不要求前端填写城市和天数，
 * 这些字段由多模态模型识别；一旦转换完成，后续 Planner 和 Review 就无需区分请求来源。
 */
@Component
public class XhsNoteTripRequestFactory {

    /**
     * 校验模型推导出的核心旅行参数，并生成旧流程能够识别的请求对象。
     */
    public TripRequest create(XhsNotePlanRequest source, XhsNoteUnderstandingResult understanding) {
        // 过滤掉模型偶尔产生的空城市项，避免它们继续进入高德查询。
        List<CityStay> cities = understanding.safeCityStays().stream()
            .filter(city -> city != null && city.city() != null && !city.city().isBlank())
            .toList();
        if (cities.isEmpty()) {
            throw new BizException("无法从笔记中识别目的地城市，请在要求中补充目的地。");
        }

        // 总天数优先取 travel_days，缺失时 XhsNoteUnderstandingResult 会从城市或 Day 路线推导。
        int travelDays = understanding.resolvedTravelDays();
        if (travelDays <= 0) {
            throw new BizException("无法从笔记中识别旅行天数，请在要求中补充天数。");
        }
        // 多城市行程必须满足“各城市停留天数之和 = 总天数”，否则 Planner 会出现日期错位。
        int cityDays = cities.stream().mapToInt(CityStay::safeDays).sum();
        if (cityDays != travelDays) {
            throw new BizException("笔记识别出的城市停留天数与总天数不一致，请检查多模态输出。");
        }

        // 请求进入此处前已经校验 yyyy-MM-dd，因此可以直接使用 LocalDate 计算结束日期。
        LocalDate startDate = LocalDate.parse(source.safeStartDate());
        LocalDate endDate = startDate.plusDays(travelDays - 1L);
        // 城市、交通和住宿来自笔记理解；free_text_input 继续携带用户额外要求。
        return new TripRequest(
            cities.getFirst().city(),
            cities,
            startDate.toString(),
            endDate.toString(),
            travelDays,
            understanding.safeTransportation(),
            understanding.safeAccommodation(),
            understanding.safePreferences(),
            source.safeRequirement(),
            "zh"
        );
    }
}
