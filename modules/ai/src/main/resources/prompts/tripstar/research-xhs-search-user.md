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
1. 对每个城市先调用 xhs_search_notes，关键词固定优先使用“城市名 + 旅游攻略”，例如“北京旅游攻略”。
2. 如果第一次工具返回 success=true 但 data 为空，再搜索 1 到 2 个短关键词，例如“城市名 + 景点攻略”“城市名 + 美食攻略”。
3. 如果用户有老人、亲子、不想太累等强偏好，可以再搜索一个短关键词，例如“城市名 + 老人旅游”；不要超过 12 个中文字符。
4. 不要把总天数、交通方式、住宿偏好和多个兴趣点拼成一个长关键词。
5. xhs_search_notes 工具已经固定最多返回 5 条笔记，不要二次筛选或丢弃工具返回的笔记。
6. 如果任一工具返回 success=true 且 data 非空，必须把 data 中每一条的 note_id、title、xsec_token、xsec_source、liked_count 原样复制到 cities[].notes。
7. 如果工具返回 success=false，必须把 error 原样写入对应城市 message，notes 保持空数组。
8. 如果所有搜索都返回 success=true 但 data 为空，message 写清楚实际搜索过的关键词和“工具返回 0 条笔记”。
9. notes 中的 xsec_source 默认填写 pc_search。
10. tool_calls 必须包含实际调用过的工具名称。
11. 严禁只输出 summary 而省略 cities[].notes。

【结构化输出格式】
{{format}}
