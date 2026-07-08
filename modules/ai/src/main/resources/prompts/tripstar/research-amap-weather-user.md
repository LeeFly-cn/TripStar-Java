请查询高德天气预报。

【用户请求】
- 途经城市：{{city_names}}
- 城市停留：
{{city_stays}}
- 开始日期：{{start_date}}
- 结束日期：{{end_date}}
- 总天数：{{travel_days}}
- 输出语言：{{language}}

【执行要求】
1. 每个城市调用 amap_weather。
2. map_context.cities 中只需要填充 city 和 weatherForecasts；center、attractions、hotels、restaurants 可为空。
3. tool_calls 必须包含实际调用过的工具名称。

【结构化输出格式】
{{format}}
