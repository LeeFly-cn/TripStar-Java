请查询高德景点 POI 和城市中心经纬度。

【用户请求】
- 途经城市：{{city_names}}
- 城市停留：
{{city_stays}}
- 偏好：{{preferences}}
- 额外要求：{{free_text_input}}
- 输出语言：{{language}}

【执行要求】
1. 每个城市调用 amap_geocode 获取中心坐标。
2. 每个城市调用 amap_poi_search 查询 5 到 8 个景点、商圈或轻松游候选。
3. map_context.cities 中只需要填充 center 和 attractions；hotels、restaurants、weatherForecasts 可为空数组。
4. tool_calls 必须包含实际调用过的工具名称。

【结构化输出格式】
{{format}}
