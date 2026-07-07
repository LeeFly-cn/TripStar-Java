请先搜索小红书真实游记笔记。

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

【执行要求】
1. 对每个城市调用 xhs_search_notes。
2. 每个城市保留 3 到 5 条最适合旅行规划的笔记引用。
3. 如果工具返回 success=true 且 data 非空，必须把 data 中每一条的 note_id、title、xsec_token、xsec_source、liked_count 原样复制到 cities[].notes。
4. 如果工具返回 success=false，必须把 error 原样写入对应城市 message，notes 保持空数组。
5. 如果工具返回 success=true 但 data 为空，message 写“工具返回 0 条笔记”，notes 保持空数组。
6. notes 中的 xsec_source 默认填写 pc_search。
7. tool_calls 必须包含实际调用过的工具名称。
8. 严禁只输出 summary 而省略 cities[].notes。

【结构化输出格式】
{{format}}
