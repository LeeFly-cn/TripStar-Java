请查询高德酒店和餐饮候选。

【用户请求】
- 途经城市：{{city_names}}
- 城市停留：
{{city_stays}}
- 住宿偏好：{{accommodation}}
- 偏好：{{preferences}}
- 额外要求：{{free_text_input}}
- 输出语言：{{language}}

【执行要求】
1. 每个城市调用 amap_hotel_search，limit 建议 5 到 8。
2. 每个城市调用 amap_restaurant_search，关键词可结合城市特色、早餐、老人友好、轻松就餐等需求。
3. map_context.cities 中只需要填充 hotels 和 restaurants；center、attractions、weatherForecasts 可为空。
4. 如果 hotels 或 restaurants 任意一个数组有真实 POI，map_context.realData 必须为 true；不要因为另一类为空就把整个阶段标记为 false。
5. tool_calls 必须包含实际调用过的工具名称。

【结构化输出格式】
{{format}}
