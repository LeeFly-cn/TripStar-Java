请为下面的旅行请求收集真实上下文。

【用户请求】
- 途经城市：{{city_names}}
- 城市停留：
{{city_stays}}
- 开始日期：{{start_date}}
- 结束日期：{{end_date}}
- 总天数：{{travel_days}}
- 交通方式：{{transportation}}
- 住宿偏好：{{accommodation}}
- 偏好：{{preferences}}
- 额外要求：{{free_text_input}}
- 输出语言：{{language}}

【工具策略】
- xhs_mode：{{xhs_mode}}
- 高德工具：必须使用 amap_collect_city_context 或 amap_geocode/amap_poi_search/amap_hotel_search/amap_restaurant_search/amap_weather。
- 小红书工具：当 xhs_mode 为 tool 或 both 时必须使用 xhs_collect_city_context；当 xhs_mode 为 service 时不要调用小红书工具。

【输出要求】
1. map_context 必须根据高德工具返回内容整理。
2. content_context 必须根据小红书工具返回内容整理；如果 xhs_mode 为 service，可以返回空上下文并说明“由 service 阶段提供”。
3. excluded_places 必须提取用户明确排除的地点，例如“不想看滇池”应包含“滇池”。
4. tool_calls 记录你实际调用过的工具名称。
5. 不要使用没有工具来源的虚构数据。

【结构化输出格式】
{{format}}
