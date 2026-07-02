请检查下面的 TripPlan JSON。

用户期望总天数：{{travel_days}}
用户城市：{{city_names}}

检查规则：
1. days 数组长度必须等于用户期望总天数。
2. 每一天必须有 city、date、hotel、attractions、meals。
3. 每一天必须包含 breakfast、lunch、dinner 三餐。
4. 景点不能为空，移动日可以较少但仍需可执行。
5. 多城市时每天 city 必须合理。
6. budget.total 必须存在且大于 0。
7. 不要因为风格问题失败，只检查结构和明显不可执行的问题。

输出 JSON schema：
{
  "passed": true,
  "issues": ["问题1"],
  "suggestions": ["建议1"]
}

TripPlan JSON：
{{trip_plan_json}}
