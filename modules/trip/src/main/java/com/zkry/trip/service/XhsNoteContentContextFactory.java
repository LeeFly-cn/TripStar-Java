package com.zkry.trip.service;

import com.zkry.common.core.constant.TravelDataSource;
import com.zkry.common.json.utils.JsonUtils;
import com.zkry.content.dto.ContentAttractionCandidate;
import com.zkry.content.dto.ContentCityContext;
import com.zkry.content.dto.ContentPlanningContext;
import com.zkry.trip.dto.CityStay;
import com.zkry.trip.dto.TripRequest;
import com.zkry.trip.dto.xhsnote.XhsNotePlace;
import com.zkry.trip.dto.xhsnote.XhsNoteUnderstandingResult;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 将多模态理解结果转换成现有 Planner Agent 使用的 {@link ContentPlanningContext}。
 *
 * <p>Planner 原本读取的是“小红书搜索与详情 Agent”产出的内容上下文。指定笔记模式通过
 * 这个 Factory 生成相同数据结构，因此不用给 Planner 增加另一套入参和分支。
 */
@Component
public class XhsNoteContentContextFactory {

    /** 为每个目的地城市建立一份笔记内容上下文。 */
    public ContentPlanningContext create(TripRequest trip, XhsNoteUnderstandingResult understanding) {
        // 保存完整结构化结果，Planner 除了景点候选外仍可看到酒店、餐饮和每日路线等原始事实。
        String rawText = JsonUtils.toJsonString(understanding);
        List<ContentCityContext> cities = trip.normalizedCities().stream()
            .map(city -> cityContext(city, trip.primaryCity(), understanding, rawText))
            .toList();
        return new ContentPlanningContext(
            cities,
            !cities.isEmpty(),
            TravelDataSource.XHS_NOTE,
            "已读取并理解用户指定的小红书笔记。"
        );
    }

    /** 把模型识别出的景点按城市分配到 ContentCityContext。 */
    private ContentCityContext cityContext(
        CityStay city,
        String primaryCity,
        XhsNoteUnderstandingResult understanding,
        String rawText
    ) {
        List<ContentAttractionCandidate> attractions = understanding.safeAttractions().stream()
            .filter(place -> belongsToCity(place, city.city(), primaryCity))
            .map(this::candidate)
            .toList();
        return new ContentCityContext(
            city.city(),
            "用户指定小红书笔记",
            TravelDataSource.XHS_NOTE,
            rawText,
            attractions,
            safe(understanding.summary())
        );
    }

    /**
     * 判断地点属于哪个城市。
     *
     * <p>模型未填写 city 时，把地点归到主城市，而不是复制到所有城市，防止 Planner 重复安排。
     */
    private boolean belongsToCity(XhsNotePlace place, String city, String primaryCity) {
        if (place == null) {
            return false;
        }
        if (place.city() == null || place.city().isBlank()) {
            return city.equals(primaryCity);
        }
        return city.equals(place.city());
    }

    /** 将笔记地点适配成旧版景点候选，并保留来源笔记和建议天数。 */
    private ContentAttractionCandidate candidate(XhsNotePlace place) {
        return new ContentAttractionCandidate(
            safe(place.name()),
            safe(place.name()),
            "",
            safe(place.reason()),
            null,
            false,
            "",
            Map.of(
                // metadata 不参与 POI 查询，但可用于后续做来源追踪和前端引用展示。
                "source_note_id", safe(place.source_note_id()),
                "day", place.day() == null ? 0 : place.day()
            )
        );
    }

    /** Content DTO 使用空字符串表达缺失文本。 */
    private String safe(String value) {
        return value == null ? "" : value;
    }
}
