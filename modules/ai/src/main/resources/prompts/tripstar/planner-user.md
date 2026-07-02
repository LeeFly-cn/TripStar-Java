请为下面的用户生成旅行计划 JSON。

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

【地图/POI/天气上下文】
{{map_context}}

【小红书游记上下文】
{{content_context}}

【必须输出的 JSON schema】
{
  "city": "主城市",
  "cities": ["城市1", "城市2"],
  "start_date": "YYYY-MM-DD",
  "end_date": "YYYY-MM-DD",
  "days": [
    {
      "date": "YYYY-MM-DD",
      "day_index": 0,
      "city": "当天所在城市",
      "is_transfer_day": false,
      "transfer_info": "",
      "description": "当天行程说明",
      "transportation": "交通方式",
      "accommodation": "住宿说明",
      "hotel": {
        "name": "酒店名",
        "address": "酒店地址",
        "location": {"longitude": 116.397, "latitude": 39.908},
        "price_range": "价格范围",
        "rating": "评分",
        "distance": "距离说明",
        "type": "酒店类型",
        "estimated_cost": 400
      },
      "attractions": [
        {
          "name": "景点名",
          "address": "景点地址",
          "location": {"longitude": 116.397, "latitude": 39.908},
          "visit_duration": 120,
          "description": "景点说明、预约提醒、避坑提示",
          "category": "景点类型",
          "rating": 4.7,
          "image_url": "",
          "ticket_price": 60
        }
      ],
      "meals": [
        {
          "type": "breakfast",
          "name": "餐饮名称",
          "address": "地址",
          "location": {"longitude": 116.397, "latitude": 39.908},
          "description": "推荐理由",
          "estimated_cost": 40
        }
      ]
    }
  ],
  "weather_info": [
    {
      "date": "YYYY-MM-DD",
      "city": "城市",
      "day_weather": "晴",
      "night_weather": "多云",
      "day_temp": 26,
      "night_temp": 18,
      "wind_direction": "东南风",
      "wind_power": "3级"
    }
  ],
  "overall_suggestions": "总体建议",
  "budget": {
    "total_attractions": 300,
    "total_hotels": 1200,
    "total_meals": 600,
    "total_transportation": 500,
    "total_inter_city_transport": 0,
    "total": 2600
  }
}

【要求】
1. days 数组长度必须等于总天数。
2. 每天安排 2-3 个景点，移动日可以 1-2 个。
3. 每天必须包含 breakfast、lunch、dinner 三餐。
4. 每天必须有一个具体 hotel。
5. location 必须给出合理经纬度，没有精确信息时给城市附近的近似坐标。
6. 多城市时，每天 city 字段必须正确，切换城市当天设置 is_transfer_day=true。
7. 只输出 JSON 对象，不要输出 markdown。
8. 如果地图上下文里有 POI、酒店、餐饮、天气，请优先使用其中的真实名称、地址和经纬度；数量不足时再由你补齐。
9. 景点顺序要按同城就近、少走回头路的原则安排；移动日行程要轻。
10. 如果小红书游记上下文里有景点候选、预约提醒、避坑建议，请优先吸收进 attractions.description，并保留 reservation_required / reservation_tips 含义。
