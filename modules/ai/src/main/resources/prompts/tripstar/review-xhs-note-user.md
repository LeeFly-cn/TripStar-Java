请检查下面由用户指定小红书笔记生成的 TripPlan JSON。

用户期望总天数：{{travel_days}}
用户城市：{{city_names}}

检查规则：
1. days 数组长度必须等于用户期望总天数。
2. 每一天必须有 city、date、hotel、attractions、meals。
3. 每一天必须包含 breakfast、lunch、dinner 三餐。
4. 景点不能为空。
5. 指定笔记模式不限制每天景点数量。即使某天景点较多，也不能仅因此判定失败；可以在 suggestions 中提醒行程紧凑。
6. 多城市时每天 city 必须合理。
7. budget.total 必须存在且大于 0。
8. 不要因为风格问题失败，只检查结构缺失、日期错误或明显数据矛盾。

结构化输出格式：
{{format}}

TripPlan JSON：
{{trip_plan_json}}
