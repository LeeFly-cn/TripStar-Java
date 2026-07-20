package com.zkry.trip.dto.xhsnote;

import com.zkry.trip.dto.CityStay;
import java.util.List;
import java.util.Objects;

/**
 * 指定笔记经过多模态模型理解后的结构化结果。
 *
 * <p>这个 DTO 同时保存旅行参数和笔记事实。后续 Java 代码先用旅行参数构造
 * {@code TripRequest}，再把景点、酒店和餐饮交给高德 Service 补全真实 POI。
 *
 * @param city 单城市行程的主城市，兼容模型只返回一个城市的情况
 * @param city_stays 多城市及各自停留天数
 * @param travel_days 总旅行天数
 * @param transportation 从笔记和要求推导出的主要交通方式
 * @param accommodation 住宿类型偏好
 * @param preferences 旅行兴趣偏好
 * @param day_routes 从正文或图片中识别出的 Day01、Day02 路线
 * @param attractions 景点和体验地点
 * @param hotels 笔记明确提到的酒店或住宿区域
 * @param restaurants 笔记明确提到的餐厅、美食或用餐区域
 * @param transport_notes 交通、换乘、停车等笔记事实
 * @param excluded_places 用户明确排除的地点
 * @param warnings 内容冲突、日期不完整等需要 Planner 注意的问题
 * @param summary 模型对全部笔记的简短归纳
 */
public record XhsNoteUnderstandingResult(
    String city,
    List<CityStay> city_stays,
    Integer travel_days,
    String transportation,
    String accommodation,
    List<String> preferences,
    List<XhsNoteDayRoute> day_routes,
    List<XhsNotePlace> attractions,
    List<XhsNotePlace> hotels,
    List<XhsNotePlace> restaurants,
    List<String> transport_notes,
    List<String> excluded_places,
    List<String> warnings,
    String summary
) {
    /**
     * 返回标准化城市列表；模型只填 city 时，根据总天数自动构造单城市停留信息。
     */
    public List<CityStay> safeCityStays() {
        if (city_stays != null && !city_stays.isEmpty()) {
            return city_stays;
        }
        if (isBlank(city)) {
            return List.of();
        }
        int days = resolvedTravelDays();
        return days > 0 ? List.of(new CityStay(city, days)) : List.of();
    }

    /**
     * 按“明确总天数 -> 城市天数之和 -> 最大 Day 编号”的顺序推导旅行天数。
     */
    public int resolvedTravelDays() {
        if (travel_days != null && travel_days > 0) {
            return travel_days;
        }
        int cityDays = city_stays == null
            ? 0
            : city_stays.stream().filter(Objects::nonNull).mapToInt(CityStay::safeDays).sum();
        if (cityDays > 0) {
            return cityDays;
        }
        // 图片攻略常只写 Day01/Day02，没有单独写总天数，此时最大 Day 编号就是计划天数。
        return safeDayRoutes().stream()
            .map(XhsNoteDayRoute::day)
            .filter(Objects::nonNull)
            .mapToInt(Integer::intValue)
            .max()
            .orElse(0);
    }

    /** 返回第一个目的地城市，作为旧版 TripRequest.city。 */
    public String primaryCity() {
        List<CityStay> cities = safeCityStays();
        return cities.isEmpty() ? "" : cities.getFirst().city();
    }

    /** 模型未识别交通方式时使用项目原有默认值“公共交通”。 */
    public String safeTransportation() {
        return isBlank(transportation) ? "公共交通" : transportation.trim();
    }

    /** 模型未识别住宿偏好时使用项目原有默认值“舒适型酒店”。 */
    public String safeAccommodation() {
        return isBlank(accommodation) ? "舒适型酒店" : accommodation.trim();
    }

    /** 以下 safe 方法统一把模型可能输出的 null 集合转换成只读空列表。 */
    public List<String> safePreferences() {
        return preferences == null ? List.of() : preferences;
    }

    /** 返回每日路线安全列表。 */
    public List<XhsNoteDayRoute> safeDayRoutes() {
        return day_routes == null ? List.of() : day_routes;
    }

    /** 返回景点安全列表。 */
    public List<XhsNotePlace> safeAttractions() {
        return attractions == null ? List.of() : attractions;
    }

    /** 返回酒店安全列表。 */
    public List<XhsNotePlace> safeHotels() {
        return hotels == null ? List.of() : hotels;
    }

    /** 返回餐饮安全列表。 */
    public List<XhsNotePlace> safeRestaurants() {
        return restaurants == null ? List.of() : restaurants;
    }

    /** 返回用户明确排除地点的安全列表。 */
    public List<String> safeExcludedPlaces() {
        return excluded_places == null ? List.of() : excluded_places;
    }

    /** 返回模型识别警告的安全列表。 */
    public List<String> safeWarnings() {
        return warnings == null ? List.of() : warnings;
    }

    /**
     * 判断是否需要调用酒店补充 Agent。
     *
     * <p>没有任何酒店候选，或有效行程日中存在一天没有住宿区域，都视为缺失。
     */
    public boolean needsHotelSupplement(int travelDays) {
        if (safeHotels().isEmpty()) {
            return true;
        }
        return safeDayRoutes().stream()
            .filter(route -> route.day() == null || route.day() <= travelDays)
            .anyMatch(route -> isBlank(route.hotel_area()));
    }

    /**
     * 判断是否需要调用餐饮补充 Agent；任意有效行程日缺早餐、午餐或晚餐即需要补充。
     */
    public boolean needsMealSupplement(int travelDays) {
        if (safeDayRoutes().isEmpty()) {
            return safeRestaurants().isEmpty();
        }
        return safeDayRoutes().stream()
            .filter(route -> route.day() == null || route.day() <= travelDays)
            .anyMatch(route -> isBlank(route.breakfast_area())
                || isBlank(route.lunch_area())
                || isBlank(route.dinner_area()));
    }

    /** record 内部统一使用的空字符串判断。 */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
