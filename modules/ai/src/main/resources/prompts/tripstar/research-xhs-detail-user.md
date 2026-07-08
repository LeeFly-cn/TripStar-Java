请根据小红书搜索结果读取笔记详情，并提炼旅行规划可用的游记上下文。

【用户请求】
- 途经城市：{{city_names}}
- 城市停留：
{{city_stays}}
- 偏好：{{preferences}}
- 额外要求：{{free_text_input}}
- 输出语言：{{language}}

【小红书搜索结果】
{{xhs_search_results}}

【执行要求】
1. 对每个城市读取搜索结果 cities[].notes 中的全部笔记详情；搜索 Tool 已经固定最多 5 条，不要再二次筛选。
2. content_context.cities 每个城市至少包含 city、keyword、source、rawText、attractions、message。
3. rawText 必须来自 xhs_note_detail 工具返回的真实标题和正文，并按“笔记1/笔记2/笔记3”保留多篇笔记边界；正文过长时可以摘要，但不能只保留一篇。
4. attractions 中必须尽量保留 name、name_zh、name_en、reason、duration、reservation_required、reservation_tips。
5. tool_calls 必须包含实际调用过的工具名称。

【结构化输出格式】
{{format}}
