请根据用户指定的小红书笔记生成旅行计划 JSON。

【基本信息】
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

【高德校准后的地图/POI/天气上下文】
{{map_context}}

【小红书指定笔记的完整理解结果】
{{content_context}}

【结构化输出格式】
{{format}}

【要求】
1. days 数组长度必须等于总天数。
2. 不限制每天的景点数量。小红书笔记中的景点是用户明确选择的行程内容，不得按“每天 2-3 个景点”主动删减。
3. 优先按照游记原文摘要里的 day_routes 分配日期，并保留 places 的原始先后顺序；不要为了少走回头路擅自跨天移动或删除景点。
4. 地图上下文中的全部景点 POI 必须在 days[].attractions 中各出现一次。名称、地址、评分和经纬度使用高德校准后的数据。
5. 只有用户额外要求或 excluded_places 明确排除的地点才可以不进入行程，不能自行筛选“更值得去”的景点。
6. 每天必须包含 breakfast、lunch、dinner 三餐，并尽量靠近当天对应时段的景点。
7. 每天必须有一个具体 hotel；优先使用笔记酒店，缺失时使用高德补充结果。
8. 多城市时每天 city 必须正确，切换城市当天设置 is_transfer_day=true。
9. 如果某天景点较多，在 description 和 transportation 中说明节奏与交通建议，但仍需完整保留景点。
10. location 必须使用地图上下文中的真实经纬度，不要自己编造近似坐标。
11. 只输出 JSON 对象，不要输出 markdown、解释或注释。
12. attractions[].image_url 必须填写空字符串，后续由 Java 使用高德 POI 真实图片补充，禁止编造 example.com 或其他图片地址。
