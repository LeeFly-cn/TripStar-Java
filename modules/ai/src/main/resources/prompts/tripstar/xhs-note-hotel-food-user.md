请根据每日路线补充缺失的酒店和餐饮。

【旅行参数】
- 途经城市：{{city_names}}
- 城市停留：
{{city_stays}}
- 住宿偏好：{{accommodation}}
- 用户要求：{{free_text_input}}

【缺失项】
- 酒店缺失：{{missing_hotel}}
- 餐饮缺失：{{missing_meals}}

【每日路线锚点】
{{route_anchors}}

【执行要求】
1. 酒店缺失为 true 时，每个需要住宿的城市调用一次 amap_hotel_search，limit 使用 1。
2. 餐饮缺失为 true 时，先按 Day 顺序生成早餐、午餐、晚餐关键词列表，再调用 amap_restaurant_batch_search，limitPerKeyword 使用 1；单次最多20组，超出时按 Day 顺序分批调用。
3. 必须等待工具返回后，将真实 POI 填入 map_context.cities；禁止只输出 calls 或 arguments 调用计划。
4. map_context.cities 只填写本次新增的 hotels 和 restaurants；center 填 null，attractions 和 weatherForecasts 填空数组。
5. 只要缺失类别返回了真实 POI，map_context.realData 填 true，map_context.source 填 amap，map_context.message 简要说明补充数量。
6. tool_calls 填写实际调用过的工具名称，summary 简要说明本次补充结果。

【结构化输出格式】
{{format}}
