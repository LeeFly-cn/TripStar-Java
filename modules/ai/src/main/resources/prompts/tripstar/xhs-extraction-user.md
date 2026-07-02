请从以下真实小红书旅游游记中提炼游玩景点，输出严格 JSON 数组。

城市：{{city}}
用户偏好关键词：{{keyword}}
输出语言：{{language}}

每个对象必须包含：
- name：用于前端展示的景点名，按输出语言填写
- name_zh：中文简体官方名
- name_en：英文官方名
- reason：小红书用户真实评价、避坑建议或打卡理由
- duration：建议游玩时长，数字，单位分钟
- reservation_required：是否需要预约，布尔值
- reservation_tips：预约渠道、提前天数、限流提醒等，没有则空字符串

只提炼真实景点，不要提炼泛泛的城市、酒店、商场广告。

游记内容：
{{raw_text}}
