请查询高德景点 POI 和城市中心经纬度。

【用户请求】
- 途经城市：{{city_names}}
- 城市停留：
{{city_stays}}
- 偏好：{{preferences}}
- 额外要求：{{free_text_input}}
- 输出语言：{{language}}

【小红书提炼景点候选】
{{xhs_attractions}}

【执行要求】
1. 每个城市调用 amap_geocode 获取中心坐标。
2. 如果小红书候选景点不为空，每个候选景点优先调用 amap_poi_search 做 POI 校准；关键词优先使用候选的中文名或展示名。
3. attractions 优先放校准成功的小红书候选景点，名称、地址、经纬度以高德 POI 返回为准。
4. 如果小红书候选景点不足 5 个，再根据用户偏好补充景点、商圈或轻松游候选，补足到 5 到 8 个。
5. 如果用户要求不去某个地点，不要把该地点放入 attractions。
6. map_context.cities 中只需要填充 center 和 attractions；hotels、restaurants、weatherForecasts 可为空数组。
7. tool_calls 必须包含实际调用过的工具名称。

【结构化输出格式】
{{format}}
